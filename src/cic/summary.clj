(ns cic.summary
  (:require [cic.time :as time]
            [cic.spec :as spec]
            [cic.periods :as periods]
            [net.cgrand.xforms :as x]
            [redux.core :as redux]
            [kixi.stats.core :as k]
            [kixi.stats.distribution :as d]))

(defn placements-cost
  [placement-costs placement-counts]
  (reduce (fn [total {:keys [placement cost]}]
            (+ total (* cost (get placement-counts placement 0))))
          0 placement-costs))

(defn placement-age
  [placement age]
  (str (name placement) "-" age))

(defn periods-summary
  "Takes inferred future periods and calculates the total CiC"
  [periods dates placement-costs]
  (let [placements-zero (zipmap spec/placements (repeat 0))
        ages-zero (zipmap spec/ages (repeat 0))
        placement-ages-zero (zipmap (for [placement spec/placements age spec/ages]
                                      (placement-age placement age))
                                    (repeat 0))]
    (reduce (fn [output date]
              (let [in-care (filter (periods/in-care? date) periods)
                    by-placement (->> (map #(:placement (periods/episode-on % date)) in-care)
                                      (frequencies))
                    by-age (->> (map #(periods/age-on % date) in-care)
                                (frequencies))
                    by-placement-age (->> (map (fn [period]
                                                 (placement-age (:placement (periods/episode-on period date))
                                                                (periods/age-on period date)))
                                               in-care)
                                          (frequencies))
                    cost (placements-cost placement-costs by-placement)]
                (assoc output date {:count (count in-care)
                                    :cost cost
                                    :placements (merge-with + placements-zero by-placement)
                                    :ages (merge-with + ages-zero by-age)
                                    :placement-ages (merge-with + placement-ages-zero by-placement-age)})))
            {} dates)))


(defn mjuxt
  "A version of juxt that accepts a map of keys to functions.
  Returns a function accepts a single argument and returns a map of keys to results."
  [kvs]
  (let [[ks fns] (apply map vector kvs)]
    (comp (partial zipmap ks) (apply juxt fns))))

(defn mapm
  "Like `map`, but returns a mapping from inputs to outputs."
  [f xs]
  (->> (map (juxt identity f) xs)
       (into {})))

(defn getter
  [k]
  (fn [x]
    (get x k)))

(defn rf-for-keys
  "A reducing function which will calculate the median over vals corresponding to keys."
  [rf keys]
  (-> (mapm #(redux/pre-step rf (getter %)) keys)
      (redux/fuse)))

(defn confidence-intervals
  [dist]
  {:lower (d/quantile dist 0.05)
   :q1 (d/quantile dist 0.25)
   :median (d/quantile dist 0.5)
   :q3 (d/quantile dist 0.75)
   :upper (d/quantile dist 0.95)})

(def histogram-rf
  (redux/post-complete k/histogram confidence-intervals))

(def combo-rf
  "A reducing function which will calculate data for each output row"
  (redux/fuse {:projected (redux/pre-step histogram-rf :count)
               :projected-cost (redux/pre-step histogram-rf :cost)
               :placements (-> (rf-for-keys histogram-rf spec/placements)
                               (redux/pre-step :placements))
               :ages (-> (rf-for-keys histogram-rf spec/ages)
                         (redux/pre-step :ages))
               :placement-ages (-> (rf-for-keys histogram-rf spec/placement-ages)
                                   (redux/pre-step :placement-ages))}))

(defn grand-summary
  "A function to transduce over all runs.
  Assumes that the dates for all runs are the same.
  Calculates the summary statistics incrementally so that raw data needn't be held in memory."
  [runs]
  (let [dates (->> runs first keys)
        rf (->> (map (fn [date] (vector date (redux/pre-step combo-rf (getter date)))) dates)
                (into {})
                (redux/fuse))]
    (->> (transduce identity rf runs)
         (map (fn [[k v]] (assoc v :date k))))))

(defn episode-dates
  "Caculate the actual start and end dates for each episode within a period.
  Each episode within a period should start the day after the previous
  episode ends. We assume each episodes lasts at least one full
  day. A one-day episode will have an identical start and end date."
  [{:keys [beginning end episodes] :as period}]
  (into []
        (comp (x/partition 2 1 [nil]) ;; Simulate partition-all by padding the final partition with one nil
              (map (fn [[{:keys [placement] :as a} b]]
                     (let [start (time/days-after beginning (:offset a))
                           end (or (some->> b :offset dec (time/days-after beginning))
                                   end)]
                       (when (time/< end start)
                         (prn period))
                       (hash-map :placement placement
                                 :start start
                                 :end end)))))
        episodes))

(defn split-episode-dates-at*
  "Helper for split-episodes-dates-at"
  [date included episodes]
  (let [[{:keys [start end] :as episode} & rest] episodes]
    (if (and (seq episodes)
             episode
             (time/>= date start))
      (if (time/> end date)
        (vector (conj included (assoc episode :end date))
                (conj rest (assoc episode :start (time/days-after date 1))))
        (recur date (conj included episode) rest))
      (vector included episodes))))

(defn split-episode-dates-at
  "Given a sequence of episodes, split into two sequences
  representing before and after the provided date"
  [date episodes]
  (mapv vec (split-episode-dates-at* date [] episodes)))

(defn episodes-per-financial-year*
  "Given a sequence of episodes and financial year ends, groups the episodes by financial year"
  [episodes [year-end & more-year-ends]]
  (when (seq episodes)
    (let [[before after] (split-episode-dates-at year-end episodes)]
      (cons before
            (lazy-seq (episodes-per-financial-year* after more-year-ends))))))

(defn episodes-per-financial-year
  "Given a period, returns a map of financial year ends to the sequence of episodes within that year"
  [{:keys [beginning] :as period}]
  (let [year-ends (time/financial-year-seq beginning)]
    (zipmap (map time/year year-ends)
            (episodes-per-financial-year* (episode-dates period)
                                          year-ends))))

(defn episode-cost
  "Calculates the cost for a single episode"
  [costs-lookup {:keys [start end placement]}]
  (let [days (inc (time/day-interval start end))]
    (* days (get costs-lookup placement 0))))

(def fnil-plus (fnil + 0))
(def fnil-inc (fnil inc 0))

(defn financial-year-metrics
  "Calculates the cost per financial year for a single period"
  [costs-lookup periods]
  (reduce (fn [coll {:keys [beginning admission-age] :as period}]
            (let [year (time/year (time/financial-year-end beginning))]
              (-> (reduce (fn [coll [year episodes]]
                            (reduce (fn [coll {:keys [placement] :as episode}]
                                      (let [cost (episode-cost costs-lookup episode)]
                                        (-> coll
                                            (update-in [year :cost] fnil-plus cost)
                                            (update-in [year :placements placement] fnil-plus cost))))
                                    coll
                                    episodes))
                          coll
                          (episodes-per-financial-year period))
                  (update-in [year :joiners-ages admission-age] fnil-inc)
                  (update-in [year :joiners] fnil-inc))))
          {}
          periods))

(def annual-rf
  "A reducing function which will calculate data for each output row"
  (redux/fuse {:projected-cost (redux/pre-step histogram-rf :cost)
               :placements (-> (rf-for-keys k/median spec/placements)
                               (redux/pre-step :placements))
               :projected-joiners (redux/pre-step histogram-rf :joiners)
               :joiners-ages (-> (rf-for-keys k/median spec/ages)
                                 (redux/pre-step :joiners-ages))}))

(defn annual
  "Main entry function for calculating the cost per financial year from a sequence of runs."
  [placement-costs runs]
  (let [costs-lookup (into {} (map (juxt :placement :cost) placement-costs))
        run-costs (map #(financial-year-metrics costs-lookup %) runs)
        years (-> run-costs first keys)
        rf (->> (map (fn [year] (vector year (redux/pre-step annual-rf (getter year)))) years)
                (into {})
                (redux/fuse))]
    (->> (transduce identity rf run-costs)
         (map (fn [[k v]] (assoc v :year k))))))
