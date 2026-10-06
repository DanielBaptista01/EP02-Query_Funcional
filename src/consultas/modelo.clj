(ns consultas.modelo)

(defn consulta [] {:estagios []})

(defn acrescentar [c no]
  (update c :estagios conj no))

(defn final? [[tipo]]
  (contains? #{:selecionar :contar :soma :media :agrupar-por} tipo))

(defn identificador? [s]
  (boolean (and (string? s)
                (re-matches #"[\p{L}_][\p{L}\p{N}_-]*" s))))
