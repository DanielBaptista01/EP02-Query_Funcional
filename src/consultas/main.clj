(ns consultas.main
  (:require [clojure.string :as str]
            [consultas.core :as core]))

(defn formatar-valor [tipo valor]
  (case tipo
    :inteiro (str valor)
    :texto valor
    :decimal (String/format java.util.Locale/ROOT "%.2f" (to-array [(double valor)]))))

(defn- tipo-agregacao [tipos [op campo]]
  (case op :contar :inteiro :soma (get tipos campo) :media :decimal))

(defn formatar-resultado
  "Produz linhas sem I/O; a ordem vem da consulta ou do esquema."
  [c esquema resultado]
  (if (and (map? resultado) (:erros resultado))
    [(str "ERRO: " (str/join "; " (:erros resultado)))]
    (let [tipos (into {} esquema)
          [op argumento agregacao :as ultimo] (peek (:estagios c))]
      (cond
        (contains? #{:contar :soma :media} op)
        [(formatar-valor (tipo-agregacao tipos ultimo) resultado)]

        (= :agrupar-por op)
        (if (seq resultado)
          (map (fn [[chave valor]]
                 (str (formatar-valor (get tipos argumento) chave) "; "
                      (formatar-valor (tipo-agregacao tipos agregacao) valor)))
               resultado)
          ["(vazio)"])

        :else
        (if (seq resultado)
          (let [campos (if (= :selecionar op) argumento (map first esquema))]
            (map (fn [registro]
                   (str/join "; " (map #(formatar-valor (get tipos %) (get registro %)) campos)))
                 resultado))
          ["(vazio)"])))))

(defn responder
  "Um comando devolve linhas; nil indica EXIT. Todas as falhas viram ERRO."
  [dados linha]
  (try
    (let [comando (str/trim linha)]
      (cond
        (= comando "EXIT") nil
        (re-matches #"QUERY(?:\s+.*)?" comando)
        (let [c (core/analisar (str/trim (subs comando 5)))]
          ;; Realizacao na fronteira de I/O, para capturar erros antes de imprimir.
          (doall (formatar-resultado c (:esquema dados) (core/executar c dados))))
        (str/blank? comando) ["ERRO: linha em branco"]
        :else ["ERRO: comando desconhecido (use QUERY ou EXIT)"]))
    (catch Exception e
      [(str "ERRO: " (str/replace (or (ex-message e) "entrada invalida") #"[\r\n]+" " "))])))

(defn- terminal [dados]
  (loop []
    (print "> ")
    (flush)
    (when-let [linha (read-line)]
      (when-let [linhas (responder dados linha)]
        (doseq [resposta linhas] (println resposta))
        (recur)))))

(defn -main [& argumentos]
  (try
    (if (= 1 (count argumentos))
      (let [dados (core/ler-csv (slurp (first argumentos) :encoding "UTF-8"))]
        ;; O arquivo e finito e deve ser conferido antes de abrir o terminal.
        (terminal (update dados :registros doall)))
      (println "ERRO: uso: clojure -M:run <arquivo.csv>"))
    (catch Exception e
      (println (str "ERRO: " (str/replace (or (ex-message e) "falha ao abrir arquivo")
                                        #"[\r\n]+" " "))))))
