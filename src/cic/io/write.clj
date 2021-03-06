(ns cic.io.write
  (:require [cic.spec :as spec]
            [cic.time :as time]
            [clj-time.format :as f]
            [clojure.data.csv :as data-csv]
            [clojure.java.io :as io])
  (:import java.io.File))

(defn temp-file
  [prefix suffix]
  (doto (File/createTempFile prefix suffix)
    (.deleteOnExit)))

(def date-format
  (f/formatter :date))

(defn date->str
  [date]
  (f/unparse date-format date))

(defn projection-table
  [projection]
  (let [fields (apply juxt
                      (comp date->str :date)
                      :actual
                      :actual-cost
                      (comp :lower :projected)
                      (comp :q1 :projected)
                      (comp :median :projected)
                      (comp :q3 :projected)
                      (comp :upper :projected)
                      (comp :lower :projected-cost)
                      (comp :q1 :projected-cost)
                      (comp :median :projected-cost)
                      (comp :q3 :projected-cost)
                      (comp :upper :projected-cost)
                      (concat (map #(comp :median % :placements) spec/placements)
                              (map (fn [age] #(get-in % [:ages age :median])) spec/ages)))
        headers (concat ["Date" "Actual" "Cost"]
                        ["CiC Lower CI" "CiC Lower Quartile" "CiC Median" "CiC Upper Quartile" "CiC Upper CI"]
                        ["Cost Lower CI" "Cost Lower Quartile" "Cost Median" "Cost Upper Quartile" "Cost Upper CI"]
                        (map name spec/placements)
                        (map str spec/ages))]
    (into [headers]
          (map fields)
          projection)))

(defn period->episodes
  [{:keys [period-id simulation-number beginning dob birthday admission-age episodes end] :as period}]
  (into []
        (comp
         (filter (fn [[a b]]
                   (or (nil? b) (> (:offset b) (:offset a)))))
         (map-indexed (fn [idx [{:keys [placement offset]} to]]
                        (hash-map :period-id period-id
                                  :simulation-number simulation-number
                                  :episode-number (inc idx)
                                  :dob dob
                                  :admission-age admission-age
                                  :birthday birthday
                                  :start (time/days-after beginning offset)
                                  :end (or (some->> to :offset dec (time/days-after beginning)) end)
                                  :placement placement))))
        (partition-all 2 1 episodes)))

(defn episodes->table-rows-xf
  [project-to]
  (comp (filter (fn [{:keys [period-id dob episode start end placement]}]
                  (time/< start project-to)))
        (map (fn [{:keys [period-id simulation-number dob birthday admission-age
                          episode-number start end placement] :as episode}]
               (vector simulation-number period-id
                       episode-number dob admission-age
                       (date->str birthday)
                       (date->str start)
                       (when (time/< end project-to) (date->str end))
                       (name placement))))))

(defn episodes-table
  [project-to projections]
  (let [headers ["Simulation" "ID" "Episode" "Birth Year" "Admission Age" "Birthday" "Start" "End" "Placement"]]
    (into [headers]
          (comp cat
                (mapcat period->episodes)
                (episodes->table-rows-xf project-to))
          projections)))

(defn validation-table
  [validation]
  (let [headers ["Type" "Metric" "Actual" "Projected" "Lower Quartile" "Upper Quartile"]
        fields (juxt (comp date->str :date) :model :linear-regression :actual)]
    (->> (for [[type comparison] validation
               [value {:keys [actual projected q1 q3]}] comparison]
           (vector (name type) value actual projected q1 q3))
         (into [headers]))))

(defn annual-report-table
  [cost-projection]
  (let [headers (concat ["Financial Year End" "Joiners Actual"]
                        ["Cost Lower CI" "Cost Lower Quartile" "Cost Median" "Cost Upper Quartile" "Cost Upper CI"]
                        ["Joiners Lower CI" "Joiners Lower Quartile" "Joiners Median" "Joiners Upper Quartile" "Joiners Upper CI"]
                        (map name spec/placements)
                        (map str spec/ages))
        fields (apply juxt
                      :year
                      :actual-joiners
                      (comp :lower :projected-cost)
                      (comp :q1 :projected-cost)
                      (comp :median :projected-cost)
                      (comp :q3 :projected-cost)
                      (comp :upper :projected-cost)
                      (comp :lower :projected-joiners)
                      (comp :q1 :projected-joiners)
                      (comp :median :projected-joiners)
                      (comp :q3 :projected-joiners)
                      (comp :upper :projected-joiners)
                      (concat (map #(comp % :placements) spec/placements)
                              (map (fn [age] #(get-in % [:joiners-ages age])) spec/ages)))]
    (into [headers]
          (map fields)
          cost-projection)))

(defn placement-sequence-table
  [{:keys [projected-age-sequence-totals projected-age-totals
           actual-age-sequence-totals actual-age-totals]}]
  (let [headers ["Actual / Projected" "Age" "Placement Sequence" "Proportion"]]
    (-> (into [headers]
              (map (fn [[[age sequence] count]]
                     (vector "Projected" age sequence (double (/ count (get projected-age-totals age))))))
              projected-age-sequence-totals)
        (into (map (fn [[[age sequence] count]]
                     (vector "Actual" age sequence (double (/ count (get actual-age-totals age))))))
              actual-age-sequence-totals))))

(defn write-csv!
  [out-file tablular-data]
  (with-open [writer (io/writer out-file)]
    (data-csv/write-csv writer tablular-data)))

(defn mapseq->csv!
  [mapseq]
  (let [path (temp-file "file" ".csv")
        cols (-> mapseq first keys)]
    (->> (into [(mapv name cols)]
               (map (apply juxt cols))
               mapseq)
         (write-csv! path))
    path))
