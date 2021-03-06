(defproject cic "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/data.csv "0.1.4"]
                 [org.clojure/math.combinatorics "0.1.6"]
                 [org.clojure/spec.alpha "0.2.176"]
                 [camel-snake-kebab "0.4.0"]
                 [kixi/stats "0.5.2"]
                 [clj-time "0.15.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [net.cgrand/xforms "0.19.2"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
