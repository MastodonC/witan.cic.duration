(ns cic.model
  (:require [cic.io.read :as read]
            [cic.io.write :as write]
            [cic.random :as rand]
            [cic.rscript :as rscript]
            [cic.spec :as spec]
            [cic.time :as time]
            [clj-time.core :as t]
            [clojure.math.combinatorics :as c]
            [kixi.stats.core :as k]
            [kixi.stats.distribution :as d]
            [kixi.stats.math :as m]
            [kixi.stats.protocols :as p]))

(defn joiners-model
  "Given the date of a joiner at a particular age,
  returns the interval in days until the next joiner"
  [{:keys [model-coefs]}]
  (fn [age date seed]
    (let [day (t/in-days (t/interval (t/epoch) date))
          intercept (get model-coefs "(Intercept)")
          a (get model-coefs (str "admission_age" age) 0.0)
          b (get model-coefs "quarter")
          c (get model-coefs (str "quarter:admission_age" age) 0.0)
          n-per-quarter (m/exp (+ intercept a (* b day) (* c day)))
          n-per-day (max (/ n-per-quarter 91.3125) (/ 1 365.25)) ;; Rate per day
          ]
      (p/sample-1 (d/exponential {:rate n-per-day}) seed))))

(defn joiners-model-gen
  "Wraps R to trend joiner rates into the future."
  [periods project-to seed]
  (let [script "src/joiners.R"
        input (str (rscript/write-periods! periods))
        output (str (write/temp-file "file" ".csv"))
        seed-long (rand/rand-long seed)]
    (rscript/exec script input output
                  (time/date-as-string project-to)
                  (str (Math/abs seed-long)))
    (-> (read/joiner-csv output)
        (joiners-model))))

(defn sample-ci
  "Given a 95% lower bound, median and 95% upper bound,
  sample from a skewed normal with these properties.
  We make use of the fact that the normal 95% CI is +/- 1.96"
  [lower median upper seed]
  (let [normal (p/sample-1 (d/normal {:mu 0 :sd 1}) seed)]
    (if (pos? normal)
      (+ median (* (- upper median) (/ normal 1.96)))
      (- median (* (- median lower) (/ normal -1.96))))))

(defn clamp
  [lower x upper]
  (max (min x upper) lower))

(defn duration-model
  "Given an admitted date and age of a child in care,
  returns an expected duration in days"
  [coefs]
  (fn duration-model*
    ([birthday beginning seed]
     (duration-model* birthday beginning 0 seed))
    ([birthday beginning min-value seed]
     (let [age (time/year-interval birthday beginning)
           empirical (get coefs (max 0 (min age 17)))
           n (transduce (take-while (fn [[_ m _]] (<= m min-value))) k/count empirical)
           [r1 r2] (rand/split seed)
           max-value (dec (time/day-interval birthday (time/years-after birthday 18)))]
       (if (> n 100)
         (int (p/sample-1 (d/uniform {:a min-value :b max-value}) r1))
         (loop [r1 r1 iter 1]
           (let [quantile (int (p/sample-1 (d/uniform {:a n :b 100}) r1))
                 [lower median upper] (get empirical quantile)
                 sample (sample-ci lower median upper r2)]
             (if (and (< sample min-value) (< iter 5))
               (recur (second (rand/split r1)) (inc iter))
               (clamp min-value sample max-value)))))))))

(defn update-fuzzy
  "Like `update`, but the key is expected to be a vector of values.
  Any numeric values in the key are fuzzed, so for example
  `(update-fuzzy {} [5 0.5] conj :value)` will return:
  {(4 0) (:value),
   (4 1) (:value),
   (5 0) (:value),
   (5 1) (:value),
   (6 0) (:value),
   (6 1) (:value)}
  This enables efficient lookup of values which are similar to,
  but not neccessarily identical to, the input key."
  [coll ks f & args]
  (let [ks (mapv (fn [k]
                   (cond
                     (or (double? k) (ratio? k))
                     [(int (m/floor k)) (int (m/ceil k))]
                     (int? k)
                     [(dec k) k (inc k)]
                     :else [k]))
                 ks)]
    (reduce (fn [coll ks]
              (apply update coll ks f args))
            coll
            (apply c/cartesian-product ks))))

(defn episodes-model
  "Given an age of admission and duration,
  sample likely placements from input data"
  [closed-periods]
  (let [age-duration-lookup (reduce (fn [lookup {:keys [admission-age duration episodes period-id]}]
                                      (let [yrs (/ duration 365.0)]
                                        (update-fuzzy lookup [admission-age yrs] conj (map #(assoc % :period-id period-id) episodes))))
                                    {} closed-periods)
        age-duration-placement-offset-lookup (reduce (fn [lookup {:keys [admission-age duration episodes period-id]}]
                                                       (let [duration-yrs (/ duration 365.0)]
                                                         (reduce (fn [lookup {:keys [offset placement]}]
                                                                   (let [offset-yrs (/ offset 365)]
                                                                     (update-fuzzy lookup [admission-age duration-yrs placement offset-yrs] conj (map #(assoc % :period-id period-id) episodes))))
                                                                 lookup
                                                                 episodes)))
                                                     {} closed-periods)]
    (fn
      ([age duration seed]
       (let [duration-yrs (Math/round (/ duration 365.0))
             candidates (get age-duration-lookup [(min age 17) duration-yrs])
             candidate (rand/rand-nth candidates seed)]
         (if (seq candidate)
           (take-while #(< (:offset %) duration) candidate)
           [{:offset 0 :placement spec/unknown-placement}])))
      ([age duration {:keys [episodes] :as open-period} seed]
       (let [{:keys [placement offset]} (last episodes)]
         (let [duration-yrs (Math/round (/ duration 365.0))
               offset-yrs (Math/round (/ offset 365.0))
               candidates (get age-duration-placement-offset-lookup [(min age 17) duration-yrs placement offset-yrs])
               candidate (rand/rand-nth candidates seed)
               future-episodes (->> candidate
                                    (drop-while #(<= (:offset %) (:duration open-period)))
                                    (take-while #(< (:offset %) duration)))]
           (if (seq candidate)
             (concat episodes future-episodes)
             (let [last-offset (-> episodes last :offset)]
               (concat episodes [{:offset (inc last-offset)
                                  :placement spec/unknown-placement}])))))))))

(defn phase-duration-quantiles-model
  [coefs]
  (fn [first-phase?]
    (let [quantiles (if first-phase?
                      (:first coefs)
                      (:rest coefs))]
      (rand-nth quantiles))))

(defn phase-transitions-model
  [coefs]
  (fn [first-transition? age placement]
    (let [params  (get coefs {:first-transition first-transition?
                              :transition-age age
                              :transition-from placement})]
      (if params
        (let [[ks alphas] (apply map vector params)
              category-probs (zipmap ks (d/draw (d/dirichlet {:alphas alphas})))]
          #_(println "Found phase transition params for age" age "placement" placement "first transition" first-transition?)
          (d/draw (d/categorical category-probs)))
        (do #_(println "Didn't find phase transition params for age" age "placement" placement "first transition" first-transition?)
            placement)))))

(defn joiner-placements-model
  [coefs]
  (fn [age]
    (let [params (get coefs age)]
      (if params
        (let [[ks alphas] (apply map vector params)
              category-probs (zipmap ks (d/draw (d/dirichlet {:alphas alphas})))]
          (d/draw (d/categorical category-probs)))
        spec/unknown-placement ;; Fallback - never seen a joiner of this age
        ))))

(defn placements-model
  [{:keys [joiner-placements phase-transitions phase-duration-quantiles
           phase-bernoulli-params phase-beta-params]}]
  (let [joiner-placement (joiner-placements-model joiner-placements)
        phase-duration (phase-duration-quantiles-model phase-duration-quantiles)
        phase-transition (phase-transitions-model phase-transitions)]
    (fn [age total-duration {:keys [episodes duration beginning birthday]} seed]
      (let [episodes (if (seq episodes)
                       episodes
                       (let [placement (joiner-placement age)]
                         [{:offset 0 :placement placement}]))
            {:keys [placement offset]} (last episodes)]
        (if (and (zero? offset)
                 (> (d/draw (d/beta (get phase-bernoulli-params age))) 0.5))
          (vec episodes)
          (loop [offset offset
                 placement placement
                 placements (vec episodes)]
            (let [next-offset (loop [test-offset offset]
                                (let [age (time/year-interval birthday (time/days-after beginning test-offset))
                                      test-offset (+ test-offset (m/ceil (* (d/draw (d/beta (get phase-beta-params age))) total-duration)))]
                                  (if (> test-offset duration)
                                    test-offset
                                    (recur test-offset))))
                  age (time/year-interval birthday (time/days-after beginning next-offset))]
              (if (> next-offset total-duration)
                placements
                (let [next-placement (phase-transition (zero? offset) age placement)]
                  (recur next-offset next-placement (conj placements {:offset next-offset :placement next-placement})))))))))))

(defn period->phases
  [{:keys [birthday beginning end episodes] :as period}]
  (for [[{offset-a :offset from :placement} {offset-b :offset to :placement}] (partition-all 2 1 episodes)]
    (let [total-duration (time/day-interval beginning end)]
      {:total-duration total-duration
       :phase-duration (if offset-b
                         (- offset-b offset-a)
                         (- (time/day-interval beginning end) offset-a))
       :first-phase (zero? offset-a)
       :age (time/year-interval birthday (time/days-after beginning offset-a))})))

(defn phase-durations
  "Calculate the phase durations for all closed periods"
  [periods]
  (let [phases (into []
                     (comp (remove :open?)
                           (mapcat period->phases))
                     periods)
        input (str (rscript/write-phase-durations! phases))
        phase-duration-quantiles-out (str (write/temp-file "phase-duration-quantiles" ".csv"))
        phase-beta-params-out (str (write/temp-file "phase-beta-params" ".csv"))
        script "src/phase-durations.R"]
    (rscript/exec script input
                  phase-duration-quantiles-out
                  phase-beta-params-out)
    {:phase-duration-quantiles (read/phase-duration-quantiles-csv phase-duration-quantiles-out)
     :phase-beta-params (read/age-beta-params phase-beta-params-out)}))

(defn periods->placements-model
  [periods]
  (let [joiner-placements (reduce (fn [acc {admission-age :admission-age [{first-placement :placement}] :episodes}]
                                    (update-in acc [admission-age first-placement] (fnil inc 0)))
                                  {}
                                  periods)
        transitions (->> (mapcat (fn [{:keys [birthday beginning episodes]}]
                                   (map (fn [[{offset-a :offset from :placement} {offset-b :offset to :placement}]]
                                          {:first-transition (zero? offset-a)
                                           :transition-age (time/year-interval birthday (time/days-after beginning offset-b))
                                           :transition-from from
                                           :transition-to to})
                                        (partition 2 1 episodes)))
                                 periods)
                         (reduce (fn [m {:keys [transition-to] :as row}]
                                   (update-in m [(select-keys row [:first-transition :transition-age :transition-from]) transition-to] (fnil inc 0)))
                                 {}))
        bernoulli-params (reduce (fn [m {:keys [open? admission-age episodes]}]
                                   (if open?
                                     m
                                     (if (> (count episodes) 1)
                                       (update-in m [admission-age :beta] (fnil inc 0))
                                       (update-in m [admission-age :alpha] (fnil inc 0)))))
                                 {} periods)
        {:keys [phase-duration-quantiles phase-beta-params]} (phase-durations periods)]
    (placements-model {:joiner-placements joiner-placements
                       :phase-transitions transitions
                       :phase-duration-quantiles phase-duration-quantiles
                       :phase-bernoulli-params bernoulli-params
                       :phase-beta-params phase-beta-params})))

(defn joiner-birthday-model
  "Accepts quantiles for age zero joiner ages in days and returns a birthday-generating model
  FIXME: Create a DSDR documenting the fact that age zero joiners have been observed to join
  soon after birth. This means that age zero joiners tend disproportionately to be only days
  old when joining. Without adjusting for this we will generate too many older joiners
  which will manifest itself as an increase in age 1 year CiC, and so on. Those children
  previously would have left the system before their first birthday."
  [quantiles]
  (let [q (vec quantiles)
        n (count quantiles)
        dist (d/uniform {:a 0 :b n})]
    (fn [age join-date seed]
      (if (zero? age)
        (let [i (int (p/sample-1 dist seed))]
          (time/days-before join-date (get q i)))
        (-> (time/days-before join-date (int (p/sample-1 (d/uniform {:a 0 :b 366}) seed)))
            (time/years-before age))))))
