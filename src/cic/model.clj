(ns cic.model
  (:require [clj-time.core :as t]
            [clojure.math.combinatorics :as c]
            [clojure.test.check.random :as r]
            [kixi.stats.math :as m]
            [kixi.stats.distribution :as d]
            [kixi.stats.protocols :as p]))

(defn rand-nth-seeded
  [coll seed]
  (let [i (int (p/sample-1 (d/uniform {:a 0 :b (count coll)}) seed))]
    (get coll i)))

(defn joiners-model
  "Given the date of a joiner at a particular age,
  returns the interval in days until the next joiner"
  [{:keys [ages params]}]
  (fn [seed]
    (let [model (rand-nth-seeded ages seed)]
      (fn [age date seed]
        (let [{:keys [dispersion]} (get params age)
              shape (/ 1 dispersion)
              day (t/in-days (t/interval (t/epoch) date))

              intercept (:intercept model)
              a (get model (keyword (str "age-" age)) 0.0)
              b (get model :beginning)
              c (get model (keyword (str "beginning:age-" age)) 0.0)
              mean (m/exp (+ intercept a (* b day) (* c day)))]
          (p/sample-1 (d/gamma {:shape shape :scale (/ mean shape)}) seed))))))

(defn sample-ci
  "Given a 95% lower bound, median and 95% upper bound,
  sample from a skewed normal with these properties.
  We make use of the fact that the normal 95% CI is +/- 1.96"
  [lower median upper seed]
  (let [normal (p/sample-1 (d/normal {:mu 0 :sd 1}) seed)]
    (if (pos? normal)
      (+ median (* (- upper median) (/ normal 1.96)))
      (- median (* (- median lower) (/ normal -1.96))))))

(defn duration-model
  "Given an admitted date and age of a child in care,
  returns an expected duration in days"
  [coefs]
  (fn [age seed]
    (let [empirical (get coefs (max 0 (min age 17)))
          [r1 r2] (r/split seed)
          quantile (int (p/sample-1 (d/uniform {:a 1 :b 101}) r1))
          [lower median upper] (get empirical quantile)]
      (sample-ci lower median upper r2))))

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
  (let [age-duration-lookup (reduce (fn [lookup {:keys [admission-age duration episodes]}]
                                      (let [yrs (/ duration 365.0)]
                                        (update-fuzzy lookup [admission-age yrs] conj episodes)))
                                    {} closed-periods)
        age-duration-placement-offset-lookup (reduce (fn [lookup {:keys [admission-age duration episodes]}]
                                                       (let [duration-yrs (/ duration 365.0)]
                                                         (reduce (fn [lookup {:keys [offset placement]}]
                                                                   (let [offset-yrs (/ offset 365)]
                                                                     (update-fuzzy lookup [admission-age duration-yrs placement offset-yrs] conj episodes)))
                                                                 lookup
                                                                 episodes)))
                                                     {} closed-periods)]
    (fn
      ([age duration seed]
       (let [duration-yrs (Math/round (/ duration 365.0))
             candidates (get age-duration-lookup [(min age 17) duration-yrs])]
         (rand-nth-seeded candidates seed)))
      ([age duration {:keys [episodes] :as open-period} seed]
       (let [{:keys [placement offset]} (last episodes)]
         (let [duration-yrs (Math/round (/ duration 365.0))
               offset-yrs (Math/round (/ offset 365.0))
               candidates (get age-duration-placement-offset-lookup [(min age 17) duration-yrs placement offset-yrs])
               candidate (rand-nth-seeded candidates seed)
               future-episodes (->> candidate
                                    (drop-while #(<= (:offset %) (:duration open-period)))
                                    (take-while #(< (:offset %) duration)))]
           (concat episodes future-episodes)))))))
