(ns consultas.compilador
  (:require [consultas.modelo :as modelo]))

(defn- exigir [condicao mensagem]
  (when-not condicao (throw (ex-info mensagem {:tipo :consulta}))))

(defn- no-valido! [no tamanho]
  (exigir (and (vector? no) (= tamanho (count no)))
          (str "no malformado: " (pr-str no))))

(defn- tipo-campo [esquema campo]
  (exigir (keyword? campo) (str "campo deve ser palavra-chave: " (pr-str campo)))
  (exigir (contains? esquema campo) (str "campo inexistente: " (name campo)))
  (get esquema campo))

(defn- numerico? [tipo] (contains? #{:inteiro :decimal} tipo))

(defn- campo-numerico! [esquema campo]
  (exigir (numerico? (tipo-campo esquema campo))
          (str "agregacao exige numero no campo " (name campo))))

(defn- comparar [operacao campo literal registro]
  (operacao (compare (get registro campo) literal) 0))

(declare compilar-expressao)

(defn- logica-binaria [op esquema no]
  (no-valido! no 3)
  (let [a (compilar-expressao esquema (second no))
        b (compilar-expressao esquema (nth no 2))]
    (op a b)))

(defn- comparacao [op esquema no]
  (no-valido! no 3)
  (let [[_ campo literal] no
        tipo (tipo-campo esquema campo)]
    (exigir (or (and (numerico? tipo) (number? literal))
                (and (= :texto tipo) (string? literal)))
            (str "comparacao entre tipos incompativeis no campo " (name campo)))
    (partial comparar op campo literal)))

(defn compilar-expressao [esquema no]
  ;; Sem ramo padrao: tipos desconhecidos falham nomeando o operador.
  (case (first no)
    :e (logica-binaria (fn [a b] (fn [r] (and (a r) (b r)))) esquema no)
    :ou (logica-binaria (fn [a b] (fn [r] (or (a r) (b r)))) esquema no)
    :nao (do (no-valido! no 2)
             (complement (compilar-expressao esquema (second no))))
    := (comparacao = esquema no)
    :!= (comparacao not= esquema no)
    :< (comparacao < esquema no)
    :<= (comparacao <= esquema no)
    :> (comparacao > esquema no)
    :>= (comparacao >= esquema no)))

(defn- somar [campo registros]
  (reduce (fn [soma registro] (+' soma (get registro campo))) 0 registros))

(defn- calcular-media [campo registros]
  (let [[soma n] (reduce (fn [[soma n] registro]
                          [(+' soma (get registro campo)) (inc n)])
                        [0 0] registros)]
    (if (zero? n) 0.0 (/ (double soma) n))))

(defn- compilar-agregacao [esquema no]
  (case (first no)
    :contar (do (no-valido! no 1)
                (partial reduce (fn [n _] (inc n)) 0))
    :soma (do (no-valido! no 2)
              (campo-numerico! esquema (second no))
              (partial somar (second no)))
    :media (do (no-valido! no 2)
               (campo-numerico! esquema (second no))
               (partial calcular-media (second no)))))

(defn- ordenar [campo ordem registros]
  (sort-by campo (if (= :desc ordem) #(compare %2 %1) compare) registros))

(defn- projetar [campos registro] (select-keys registro campos))

(defn- agrupar [campo agregar registros]
  (map (fn [[chave grupo]] [chave (agregar grupo)])
       (sort-by key (group-by campo registros))))

(defn compilar-estagio [esquema no]
  (case (first no)
    :onde (do (no-valido! no 2)
              (partial filter (compilar-expressao esquema (second no))))
    :ordenar-por (let [[_ campo ordem] no]
                   (no-valido! no 3)
                   (tipo-campo esquema campo)
                   (exigir (contains? #{:asc :desc} ordem) "ordem deve ser :asc ou :desc")
                   (partial ordenar campo ordem))
    :limitar (let [n (second no)]
               (no-valido! no 2)
               (exigir (and (integer? n) (not (neg? n)))
                       "limitar exige inteiro nao negativo")
               (partial take n))
    :selecionar (let [campos (second no)]
                  (no-valido! no 2)
                  (exigir (and (vector? campos) (seq campos))
                          "selecionar exige vetor nao vazio de campos")
                  (reduce (fn [_ campo] (tipo-campo esquema campo)) nil campos)
                  (partial map (partial projetar campos)))
    (:contar :soma :media) (compilar-agregacao esquema no)
    :agrupar-por (let [[_ campo agregacao] no]
                  (no-valido! no 3)
                  (tipo-campo esquema campo)
                  (partial agrupar campo (compilar-agregacao esquema agregacao)))))

(defn compilar
  "Confere e compila cada no uma vez. Nunca acessa registros."
  [c esquema]
  (exigir (and (map? c) (vector? (:estagios c)))
          "consulta deve conter :estagios como vetor")
  (let [tipos (into {} esquema)]
    (:funcao
     (reduce (fn [{:keys [funcao final?]} no]
               (exigir (not final?) "elemento final deve ser o ultimo")
               (let [estagio (compilar-estagio tipos no)]
                 {:funcao (comp estagio funcao) :final? (modelo/final? no)}))
             {:funcao identity :final? false}
             (:estagios c)))))
