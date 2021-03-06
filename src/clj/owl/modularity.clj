(ns owl.modularity
  (:require
   [clojure.set :as set]
   [taoensso.timbre :as timbre]
   [owl.network :as network]))

(timbre/refer-timbre)

(defn node-impact
  [node]
  (reduce
   + 0
   (concat
    (vals (get node :in))
    (vals (get node :out)))))

(defn edge-set
  [node direction]
  (set (keys (get node direction))))

(defn sum-weights
  ([weights]
     (reduce + 0 (map last weights)))
  ([weights ignoring?]
     (reduce
      (fn [sum [id weight]]
        (if (ignoring? id)
          (+ sum 0)
          (+ sum weight)))
      0 weights)))

(defn initial-communities
  [network]
  (into
   {}
   (map
    (fn [id]
      [id (set [id])])
    (keys network))))

(defn prepare-network
  [network]
  (let [pared (network/remove-isolates network)
        commune
        (reduce
         (fn [network id]
           (-> network
               (assoc-in [id :community] id)
               (assoc-in [id :total-weights]
                         (+ (sum-weights (get-in network [id :in]))
                            (sum-weights (get-in network [id :out]))))))
         pared (keys pared))
        total (network/total-weights commune)]
    {:network commune
     :total total
     :ratio (/ 1.0 total)
     :communities (initial-communities commune)
     :impact (network/map-vals node-impact commune)}))

(defn weights-within
  [network community]
  (reduce
   (fn [sum id]
     (let [weights (get-in network [id :out])
           in (-> weights (select-keys community) vals)]
       (+ sum (reduce + 0 in))))
   0 community))

(defn weights-without
  [network community]
  (reduce
   (fn [sum id]
     (let [node (get network id)
           in (sum-weights (get node :in) community)
           out (sum-weights (get node :out) community)]
       (+ sum in out)))
   0 community))

(defn node-relation
  [network community to]
  (reduce
   (fn [sum id]
     (let [node (get network id)
           in (get-in node [:in to] 0.0)
           out (get-in node [:out to] 0.0)]
       (+ sum in out)))
   0 community))

(defn community-weight
  [network community]
  (reduce
   + 0
   (map
    (fn [id]
      (get-in network [id :total-weights]))
    community)))

(defn modularity-difference
  [{:keys [network ratio communities impact]} id community]
  (let [in-community (weights-within network community)
        total-community (community-weight network community)
        out-community (- total-community in-community)
        relation (node-relation network community id)
        node-impact (get impact id)
        ratio (* 0.4 ratio)

        a (* (+ in-community relation) ratio)
        b (* (+ out-community node-impact) ratio)
        c (* in-community ratio)
        d (* out-community ratio)
        e (* node-impact ratio)
        difference (- (- a (* b b)) (- c (* d d) (* e e)))]
    difference))

(defn node-connections
  [node]
  (p :connections
     (set/union
      (-> node :out keys set)
      (-> node :in keys set))))

(defn communities-for
  [network connections]
  (set (map :community (vals (select-keys network connections)))))

(defn find-community
  [{:keys [network communities] :as graph} id]
  (p :find-community
     (let [node (get network id)
           connections (node-connections node)
           potential (communities-for network connections)
           diffs (filter
                  (comp (partial <= 0) last)
                  (map
                   (fn [community]
                     (let [members (get communities community)]
                       [community (modularity-difference graph id members)]))
                   potential))]
       (if (empty? connections)
         id
         (-> (sort-by last > diffs) first first)))))

(defn community-for?
  [{:keys [network communities] :as graph} id]
  (let [node (get network id)
        connection (-> node :out keys first)
        community-id (get-in network [connection :community])
        community (get communities community-id)
        modularity (modularity-difference graph id community)]
    (if (> modularity 0)
      community-id)))

(defn community-for
  ([graph id] (community-for graph id shuffle))
  ([{:keys [network communities] :as graph} id prioritize]
   (let [node (get network id)
         current-id (:community node)
         connections (prioritize (node-connections node))]
     (if (= 1 (count (get communities current-id)))
       (if-let [connection 
                (first
                 (drop-while
                  (fn [connection]
                    (let [community-id (get-in network [connection :community])]
                      (if (= community-id current-id)
                        true
                        (let [community (get communities community-id)
                              modularity (modularity-difference graph id community)]
                          (< modularity 0)))))
                  connections))]
         (get-in network [connection :community]))))))

(defn join-community
  [graph id community]
  (let [current-id (get-in graph [:network id :community])
        current-community (get-in graph [:communities current-id])
        new-community (get-in graph [:communities community])]
    (if (= current-id community)
      graph
      (-> (reduce
           (fn [graph current]
             (assoc-in graph [:network current :community] community))
           graph current-community)
          (update-in [:communities] #(dissoc % current-id))
          (update-in [:communities community] #(set/union % current-community))))))

(defn merge-communities
  [prioritize graph]
  (reduce
   (fn [graph id]
     (if-let [community (find-community graph id)]
       (join-community graph id community)
       graph))
   graph (prioritize (-> graph :network keys))))

(defn flow-upward
  [network to community]
  (reduce
   (fn [node connections]
     (reduce
      (fn [node [connection weight]]
        (if (community connection)
          node
          (let [upward (get-in network [connection :community])]
            (update-in node [upward] #(+ (or % 0) weight)))))
      node connections))
   {} to))

(defn merge-connections
  [network community direction]
  (let [connections
        (map
         (fn [id]
           (get-in network [id direction]))
         community)
        merged (flow-upward network connections community)]
    merged))

(defn merge-nodes
  [network id community]
  (let [in (merge-connections network community :in)
        out (merge-connections network community :out)]
    {:in in
     :out out
     :community id
     :total-weights (+ (sum-weights in) (sum-weights out))}))

(defn pool-communities
  [communities full-communities]
  (if full-communities
    (network/map-map
     (fn [id above]
       (let [below (map (partial get full-communities) above)]
         [id (apply set/union below)]))
     communities)
    communities))

(defn ascend-level
  [{:keys [network total ratio communities full-communities original] :as graph}]
  (let [commune
        (reduce
         (fn [above [id community]]
           (let [aggregate (merge-nodes network id community)]
             (assoc above id aggregate)))
         {} communities)
        total (network/total-weights commune)]
    {:network commune
     :total total
     :ratio (/ 1.0 total)
     :communities (initial-communities commune)
     :impact (network/map-vals node-impact commune)
     :original (or original network)
     :full-communities (pool-communities communities full-communities)
     :sublevel (dissoc graph :original :total :ratio :impact)}))

(defn agglomerate
  [graph prioritize]
  (iterate
   (comp (partial merge-communities prioritize) ascend-level)
   (merge-communities prioritize graph)))

(defn unified?
  [graph]
  (or
   (= 1 (-> graph :communities count))
   (every?
    (partial = 1)
    (map count (-> graph :communities vals)))))

(defn community-index
  [communities]
  (into
   {}
   (map-indexed
    (fn [index id] [id index])
    (keys communities))))

(defn index-communities
  [communities]
  (let [index (community-index communities)]
    (into
     {}
     (map
      (fn [[id community]]
        [(get index id) community])
      communities))))

(defn apply-communities
  [communities network]
  (reduce
   (fn [network [community-id community]]
     (reduce
      (fn [network id]
        (assoc-in network [id :community] community-id))
      network community))
   network communities))

(defn community-map
  [network]
  (reduce
   (fn [communities [node weights]]
     (assoc communities node (:community weights)))
   {} network))

(defn unify
  ([network] (unify network (partial sort-by identity >)))
  ([network prioritize]
   (let [graph (prepare-network network)
         glom (agglomerate graph prioritize)
         quest (drop-while (comp not unified?) (rest glom))
         unity (first quest)]
     unity)))

(defn seek-unity
  ([network]
   (seek-unity
    network
    (partial
     sort-by
     (fn [id]
       (count (node-connections (get network id))))
     <)))
  ([network prioritize]
   (let [top (unify network prioritize)
         unity (loop [unity top]
                 (let [sizes (map count (vals (:full-communities unity)))
                       biggest (if (empty? sizes) 0 (apply max sizes))
                       total (reduce + 0 sizes)
                       ratio (if (zero? total) 0 (float (/ biggest total)))]
                   (if (and
                        (or
                         (< (count (:full-communities unity)) 3)
                         (< 0.85 ratio))
                        (:sublevel (:sublevel unity)))
                     (recur (:sublevel unity))
                     unity)))
         top-level (index-communities (:full-communities unity))
         unity (assoc unity :top-level-communities top-level)
         unity (update-in
                unity [:original]
                (partial apply-communities top-level))]
     (assoc unity :node-communities (community-map (:original unity))))))

(defn community-similarity
  [as bs]
  (map
   (fn [a]
     (map
      (fn [b]
        (network/jaccard-similarity a b))
      bs))
   as))
