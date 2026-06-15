(ns uno.michelada.saltrim.fmt
  "Number display masks — a pragmatic subset of the familiar spreadsheet syntax.
   Applied at DISPLAY time to a cell's computed NUMERIC value; non-numbers pass
   through untouched (so a text cell with a number mask just shows its text).

   Tokens:
     0   required digit (pads with 0)
     #   optional digit
     .   decimal point — digits after it set the number of decimal places
     ,   group thousands (a ',' anywhere in the integer part turns grouping on)
     %   scale the value by 100 and append a '%'
   Any other characters are literal prefix/suffix text. Examples:
     \"0.00\"       1234.5   -> 1234.50
     \"#,##0\"      1234567  -> 1,234,567
     \"$#,##0.00\"  1234.5   -> $1,234.50
     \"0.0%\"       0.25     -> 25.0%
     \"0 USD\"      1234     -> 1234 USD"
  (:require [clojure.string :as str]))

(defn- digits-after-dot [mask]
  (if-let [i (str/index-of mask ".")]
    (count (filter #{\0 \#} (subs mask (inc i))))
    0))

(defn- group-thousands [digits]
  (->> (reverse digits)
       (partition-all 3)
       (map #(str/join (reverse %)))
       reverse
       (str/join ",")))

(defn apply-mask
  "Render number `n` per `mask`. Blank mask or a non-number returns `(str n)`
   (nil -> \"\"), so masks are a no-op on text and errors fall through."
  [mask n]
  (cond
    (nil? n)                       ""
    (or (str/blank? mask)
        (not (number? n)))         (str n)
    :else
    (let [pct?   (str/includes? mask "%")
          x      (cond-> (double n) pct? (* 100.0))
          dec-n  (digits-after-dot mask)
          group? (str/includes? (first (str/split mask #"\." 2)) ",")
          neg?   (neg? x)
          scaled (Math/round (* (Math/abs x) (Math/pow 10 dec-n)))
          s      (str scaled)
          s      (if (< (count s) (inc dec-n))                 ; left-pad for the int digit
                   (str (str/join (repeat (- (inc dec-n) (count s)) \0)) s)
                   s)
          cut    (- (count s) dec-n)
          intd   (cond-> (subs s 0 cut) group? group-thousands)
          decd   (subs s cut)
          prefix (re-find #"^[^0#.,%]*" mask)
          suffix (re-find #"[^0#.,%]*$" mask)]
      (str prefix
           (when neg? "-") intd
           (when (pos? dec-n) (str "." decd))
           suffix
           (when pct? "%")))))
