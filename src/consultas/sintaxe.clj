(ns consultas.sintaxe
  (:require [clojure.string :as str]
            [consultas.modelo :as modelo]))

(defn- erro [mensagem tokens]
  (throw (ex-info mensagem {:tipo :sintaxe :token (first tokens)})))

(defn tokenizar
  "Reconhece inclusive delimitadores dentro de textos, sem avaliar Clojure."
  [texto]
  (loop [resto texto tokens []]
    (if (empty? resto)
      tokens
      (cond
        (re-find #"^\s+" resto)
        (recur (subs resto (count (re-find #"^\s+" resto))) tokens)

        (str/starts-with? resto "\"")
        (if-let [texto-literal (re-find #"^\"[^\"\r\n]*\"" resto)]
          (recur (subs resto (count texto-literal))
                 (conj tokens [:literal (subs texto-literal 1 (dec (count texto-literal)))]))
          (erro "texto sem aspas de fechamento" tokens))

        :else
        (if-let [lexema (re-find #"^(?:!=|<=|>=|[=<>|(),]|[+-]?(?:[0-9]+\.[0-9]+|[0-9]+)|[\p{L}_][\p{L}\p{N}_-]*)" resto)]
          (let [token (cond
                        (re-matches #"[+-]?[0-9]+\.[0-9]+" lexema)
                        [:literal (Double/parseDouble lexema)]
                        (re-matches #"[+-]?[0-9]+" lexema)
                        [:literal (bigint lexema)]
                        :else lexema)]
            (recur (subs resto (count lexema)) (conj tokens token)))
          (erro (str "caractere inesperado: " (first resto)) tokens))))))

(defn- esperar [tokens palavra]
  (if (= palavra (first tokens))
    (rest tokens)
    (erro (str "esperado: " palavra) tokens)))

(defn- campo [tokens]
  (if (modelo/identificador? (first tokens))
    [(keyword (first tokens)) (rest tokens)]
    (erro "nome de campo esperado" tokens)))

(defn- literal [tokens]
  (let [t (first tokens)]
    (if (and (vector? t) (= :literal (first t)))
      [(second t) (rest tokens)]
      (erro "literal esperado (numero ou texto entre aspas)" tokens))))

(declare expressao)

(defn- primaria [tokens]
  (if (= "(" (first tokens))
    (let [[no resto] (expressao (rest tokens))]
      [no (esperar resto ")")])
    (let [[nome resto] (campo tokens)
          op (first resto)]
      (when-not (contains? #{"=" "!=" "<" "<=" ">" ">="} op)
        (erro "operador de comparacao esperado" resto))
      (let [[valor sobra] (literal (rest resto))]
        [[(keyword op) nome valor] sobra]))))

(defn- negacao [tokens]
  (if (= "nao" (first tokens))
    (let [[no resto] (negacao (rest tokens))] [[:nao no] resto])
    (primaria tokens)))

(defn- cadeia [analisar-operando palavra operador tokens]
  (let [[inicio resto] (analisar-operando tokens)]
    (loop [no inicio sobra resto]
      (if (= palavra (first sobra))
        (let [[direita restantes] (analisar-operando (rest sobra))]
          (recur [operador no direita] restantes))
        [no sobra]))))

(defn- conjuncao [tokens] (cadeia negacao "e" :e tokens))
(defn- expressao [tokens] (cadeia conjuncao "ou" :ou tokens))

(defn- campos [tokens]
  (let [[nome resto] (campo tokens)]
    (loop [nomes [nome] sobra resto]
      (if (= "," (first sobra))
        (let [[proximo restantes] (campo (rest sobra))]
          (recur (conj nomes proximo) restantes))
        [nomes sobra]))))

(defn- agregacao [tokens]
  (let [[tipo & resto] tokens]
    (case tipo
      "contar" [[:contar] resto]
      "soma" (let [[nome sobra] (campo resto)] [[:soma nome] sobra])
      "media" (let [[nome sobra] (campo resto)] [[:media nome] sobra])
      (erro "agregacao esperada: contar, soma ou media" tokens))))

(defn- elemento [tokens]
  (let [[tipo & resto] tokens]
    (case tipo
      "onde" (let [[expr sobra] (expressao resto)] [[:onde expr] sobra])
      "ordenar" (let [[nome sobra] (campo (esperar resto "por"))]
                  (if (= "desc" (first sobra))
                    [[:ordenar-por nome :desc] (rest sobra)]
                    [[:ordenar-por nome :asc] sobra]))
      "limitar" (let [[n sobra] (literal resto)]
                  (when-not (and (integer? n) (not (neg? n)))
                    (erro "limitar exige inteiro nao negativo" resto))
                  [[:limitar n] sobra])
      "selecionar" (let [[nomes sobra] (campos resto)] [[:selecionar nomes] sobra])
      ("contar" "soma" "media") (agregacao tokens)
      "agrupar" (let [[nome sobra] (campo (esperar resto "por"))
                      [agg restantes] (agregacao (esperar sobra "com"))]
                  [[:agrupar-por nome agg] restantes])
      (erro (str "elemento desconhecido ou vazio: " tipo) tokens))))

(defn analisar [texto]
  (when-not (string? texto) (erro "consulta deve ser texto" []))
  (loop [c (modelo/consulta) tokens (seq (tokenizar texto))]
    (if-not tokens
      c
      (let [[no resto] (elemento tokens)
            nova (modelo/acrescentar c no)]
        (cond
          (empty? resto) nova
          (modelo/final? no) (erro "elemento final deve ser o ultimo" resto)
          (= "|" (first resto))
          (if (seq (rest resto))
            (recur nova (rest resto))
            (erro "elemento vazio depois de |" resto))
          :else (erro "esperado | entre elementos" resto))))))
