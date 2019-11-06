(ns cic.io.read
  (:require [camel-snake-kebab.core :as csk]
            [clj-time.format :as f]
            [clojure.data.csv :as data-csv]
            [clojure.java.io :as io]
            [clojure.set :as cs]
            [clojure.string :as str]))

(defn blank-row? [row]
  (every? str/blank? row))

(def date-format
  (f/formatter :date))

(defn parse-date
  [s]
  (f/parse date-format s))

(defn parse-double
  [d]
  (Double/parseDouble d))

(defn parse-int
  [i]
  (int (Double/parseDouble i)))

(defn parse-placement
  [s]
  (let [placement (keyword s)]
    (cond
      (#{:U1 :U2 :U3} placement) :Q1
      (#{:U4 :U5 :U6} placement) :Q2
      :else placement)))

(defn format-episode
  [row]
  (-> (cs/rename-keys row {:id :child-id :care-status :CIN})
      (update :child-id #(Long/parseLong %))
      (update :dob #(Long/parseLong %))
      (update :report-date parse-date)
      (update :ceased #(when-not (str/blank? %) (parse-date %)))
      (update :report-year #(Long/parseLong %))
      (update :placement parse-placement)
      (update :CIN keyword)
      (update :legal-status keyword)
      (update :uasc (comp boolean #{"True"}))))

(defn load-csv
  "Loads csv file with each row as a vector.
   Stored in map separating column-names from data"
  [filename]
  (let [parsed-csv (with-open [in-file (io/reader filename)]
                     (->> in-file
                          data-csv/read-csv
                          (remove blank-row?)
                          (vec)))
        parsed-data (rest parsed-csv)
        headers (map csk/->kebab-case-keyword (first parsed-csv))]
    (map (partial zipmap headers) parsed-data)))

(defn episodes
  [filename]
  (->> (load-csv filename)
       (map format-episode)))

(defn duration-csv
  "Helper function for duration-csvs"
  [filename]
  (let [parsed-csv (with-open [in-file (io/reader filename)]
                     (->> in-file
                          data-csv/read-csv
                          (remove blank-row?)
                          (vec)))
        parsed-data (rest parsed-csv)]
    (->> (map (comp (juxt first (comp vec rest)) (partial map parse-int)) parsed-data)
         (into {}))))

(defn duration-csvs
  "Loads three files containing the lower, median and upper bounds
  of duration in care by age of entry.
  Each of these files is output by a survival analysis in R."
  [lower-filename median-filename upper-filename]
  (let [lower (duration-csv lower-filename)
        median (duration-csv median-filename)
        upper (duration-csv upper-filename)]
    (->> (for [age (range 0 19)]
           (let [lower (get lower age)
                 median (get median age)
                 upper (get upper age)]
             (vector age
                     (mapv (fn [l m u]
                             (vector l m u))
                           lower median upper))))
         (into {}))))

(defn joiner-csvs
  "Loads the two files required for projecting joiners.
  The first is a sample of coefficients from the GLM (see coef.samples in joiners.R)
  The second is an inference of the gamma dispersion by age (see gamma.rates in joiners.R)
  although currently the same dispersion is reported for all ages."
  [coefs-filename dispersion-filename]
  (let [model-coefs (->> (load-csv coefs-filename)
                         (first) ;; We're only interested in the first row
                         (reduce-kv (fn [coll k v] (assoc coll k (parse-double v))) {}))
        gamma-params (->> (load-csv dispersion-filename)
                          (map #(-> %
                                    (update :admission-age parse-int)
                                    (update :shape parse-double)
                                    (update :rate parse-double)
                                    (update :dispersion parse-double)))
                          (map (juxt :admission-age identity))
                          (into {}))]
    {:model-coefs model-coefs :gamma-params gamma-params}))

(defn costs-csv
  [filename]
  (->> (load-csv filename)
       (map #(-> %
                 (update :placement keyword)
                 (update :cost parse-double)))))