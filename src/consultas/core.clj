(ns consultas.core
  (:require [clojure.string :as str]
            [consultas.modelo :as modelo]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

(defn consulta [] (modelo/consulta))
(defn onde [c expr] (modelo/acrescentar c [:onde expr]))
(defn selecionar [c campos] (modelo/acrescentar c [:selecionar campos]))
(defn ordenar-por [c campo ordem] (modelo/acrescentar c [:ordenar-por campo ordem]))
(defn limitar [c n] (modelo/acrescentar c [:limitar n]))
(defn contar [c] (modelo/acrescentar c [:contar]))
(defn soma [c campo] (modelo/acrescentar c [:soma campo]))
(defn media [c campo] (modelo/acrescentar c [:media campo]))
(defn agrupar-por [c campo agregacao] (modelo/acrescentar c [:agrupar-por campo agregacao]))
(defn analisar [texto] (sintaxe/analisar texto))

(defn executar [c dados]
  ;; A funcao so recebe os registros apos a conferencia integral da consulta.
  (try
    (let [f (compilador/compilar c (:esquema dados))]
      (f (:registros dados)))
    (catch clojure.lang.ExceptionInfo e
      {:erros [(ex-message e)]})))

(defn- erro-csv [mensagem]
  (throw (ex-info mensagem {:tipo :csv})))

(defn- declaracao [texto]
  (let [partes (str/split texto #":" -1)
        [nome tipo] (map str/trim partes)]
    (when-not (and (= 2 (count partes)) (modelo/identificador? nome)
                   (contains? #{"inteiro" "decimal" "texto"} tipo))
      (erro-csv (str "declaracao de campo invalida: " texto)))
    [(keyword nome) (keyword tipo)]))

(defn- converter [tipo valor campo linha]
  (let [numero (str/trim valor)]
    (when (str/blank? valor)
      (erro-csv (str "campo vazio: " (name campo) " na linha " linha)))
    (try
      (case tipo
        :texto valor
        :inteiro (if (re-matches #"[+-]?[0-9]+" numero)
                   (bigint numero)
                   (erro-csv (str "inteiro invalido no campo " (name campo) " na linha " linha)))
        :decimal (let [n (Double/parseDouble numero)]
                   (when-not (Double/isFinite n)
                     (erro-csv (str "decimal invalido no campo " (name campo) " na linha " linha)))
                   n))
      (catch NumberFormatException _
        (erro-csv (str "numero invalido no campo " (name campo) " na linha " linha))))))

(defn- registro [esquema indice linha]
  (let [valores (str/split linha #";" -1)
        numero (+ indice 2)]
    (when-not (= (count esquema) (count valores))
      (erro-csv (str "quantidade de campos incorreta na linha " numero)))
    (into {} (map (fn [[campo tipo] valor]
                    [campo (converter tipo valor campo numero)])
                  esquema valores))))

(defn ler-csv
  "Transforma texto CSV em dados; leitura do arquivo pertence a main."
  [texto]
  (let [linhas-todas (str/split (str/replace-first texto #"^\uFEFF" "") #"\r\n|\n|\r" -1)
        [cabecalho & linhas] (if (= "" (peek linhas-todas))
                              (pop linhas-todas)
                              linhas-todas)]
    (when (str/blank? cabecalho) (erro-csv "CSV sem cabecalho"))
    (let [esquema (mapv declaracao (str/split cabecalho #";" -1))
          campos (map first esquema)]
      (when-not (= (count campos) (count (distinct campos)))
        (erro-csv "campos duplicados no cabecalho"))
      {:esquema esquema
       :registros (map-indexed (partial registro esquema) linhas)})))
