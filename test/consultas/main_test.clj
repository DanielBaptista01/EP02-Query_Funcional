(ns consultas.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [consultas.core :as core]
            [consultas.compilador :as compilador]
            [consultas.main :as main]))

(defn- ler-casos [texto]
  (loop [[linha & resto] (str/split-lines texto) pendente nil casos []]
    (if-not linha
      casos
      (let [limpa (str/trim linha) seta (str/index-of linha "->")]
        (cond
          (or (str/blank? limpa) (str/starts-with? limpa "#"))
          (recur resto pendente casos)

          seta
          (let [antes (str/trim (subs linha 0 seta))
                comando (if (str/blank? antes) (or pendente "") antes)
                esperado (str/trim (subs linha (+ seta 2)))]
            (recur resto nil (conj casos {:comando comando :esperado [esperado]})))

          (str/starts-with? limpa "QUERY") (recur resto limpa casos)

          :else (recur resto pendente (update-in casos [(dec (count casos)) :esperado] conj limpa)))))))

(defn- dados-genericos []
  (core/ler-csv (str "item:texto;categoria:texto;quantidade:inteiro;preco:decimal\n"
                     "B;x;2;8.0\nA;y;3;8.0\nC;x;4;1.5\n")))

(defn- resultado [texto]
  (core/executar (core/analisar texto) (dados-genericos)))

(deftest bateria-oficial
  (let [dados (core/ler-csv (slurp "dados/filmes.csv"))
        casos (ler-casos (slurp "casos_teste_ep02.txt"))]
    (is (= 48 (count casos)))
    (reduce
     (fn [n {:keys [comando esperado]}]
       (testing comando
         (let [obtido (main/responder dados comando)
               primeira (first esperado)]
           (cond
             (= comando "EXIT") (is (nil? obtido))
             (str/starts-with? primeira "ERRO")
             (do (is (= 1 (count obtido)))
                 (is (str/starts-with? (first obtido) "ERRO:"))
                 (when-let [[_ campo] (re-find #"cita ([\p{L}_]+)" primeira)]
                   (is (str/includes? (first obtido) campo))))
             :else (is (= esperado (vec obtido))))))
       (inc n))
     0 casos)))

(deftest builder-imutavel-e-igualdade
  (let [base (core/consulta)
        filtrada (core/onde base [:>= :quantidade 2])
        dois (core/limitar filtrada 2)
        tres (core/limitar filtrada 3)]
    (is (= {:estagios []} base))
    (is (= {:estagios [[:onde [:>= :quantidade 2]]]} filtrada))
    (is (= dois (core/analisar "onde quantidade >= 2 | limitar 2")))
    (is (= tres (core/analisar "onde quantidade >= 2 | limitar 3")))
    (is (= 2 (count (core/executar dois (dados-genericos)))))
    (is (= 3 (count (core/executar tres (dados-genericos)))))))

(deftest builder-cobre-toda-linguagem
  (let [c (core/consulta)]
    (is (= (core/analisar "") c))
    (is (= (core/analisar "onde preco = 8.0") (core/onde c [:= :preco 8.0])))
    (is (= (core/analisar "ordenar por item") (core/ordenar-por c :item :asc)))
    (is (= (core/analisar "ordenar por preco desc") (core/ordenar-por c :preco :desc)))
    (is (= (core/analisar "selecionar item, preco") (core/selecionar c [:item :preco])))
    (is (= (core/analisar "contar") (core/contar c)))
    (is (= (core/analisar "soma quantidade") (core/soma c :quantidade)))
    (is (= (core/analisar "media preco") (core/media c :preco)))
    (is (= (core/analisar "agrupar por categoria com contar")
           (core/agrupar-por c :categoria [:contar])))))

(deftest esquema-desconhecido-e-ordem
  (is (= ["A; 8.00" "B; 8.00" "C; 1.50"]
         (main/responder (dados-genericos) "QUERY ordenar por item | selecionar item, preco")))
  (is (= ["B; 2" "A; 3"]
         (main/responder (dados-genericos) "QUERY ordenar por preco desc | limitar 2 | selecionar item, quantidade")))
  (is (= ["B" "A"] (mapv :item (resultado "limitar 2 | ordenar por preco"))))
  (is (= ["C" "B"] (mapv :item (resultado "ordenar por preco | limitar 2"))))
  (is (= [["x" 6] ["y" 3]] (vec (resultado "agrupar por categoria com soma quantidade")))))

(deftest operadores-e-precedencia
  (let [casos [["quantidade = 3" 1] ["quantidade != 3" 2]
               ["quantidade < 3" 1] ["quantidade <= 3" 2]
               ["quantidade > 3" 1] ["quantidade >= 3" 2]
               ["item < \"B\"" 1] ["item >= \"B\"" 2]
               ["preco = 8" 2] ["quantidade = 3.0" 1]
               ["nao nao quantidade = 3" 1]
               ["quantidade = 2 ou quantidade = 3 e preco < 2" 1]
               ["(quantidade = 2 ou quantidade = 3) e preco < 2" 0]
               ["nao (quantidade = 2 ou quantidade = 3)" 1]]]
    (reduce (fn [_ [expr n]]
              (testing expr (is (= n (resultado (str "onde " expr " | contar"))))))
            nil casos)))

(deftest textos-com-delimitadores-e-campos-unicode
  (let [dados (core/ler-csv "descrição:texto;valor-total:inteiro\na | b, (c) >= d;3\n")]
    (is (= ["a | b, (c) >= d; 3"]
           (main/responder dados "QUERY onde descrição = \"a | b, (c) >= d\"")))
    (is (= 3 (core/executar (core/analisar "soma valor-total") dados))))
  (is (= "" (nth (second (first (:estagios (core/analisar "onde item = \"\"")))) 2))))

(deftest agregacoes-e-vazios
  (is (= 9 (resultado "soma quantidade")))
  (is (= 17.5 (resultado "soma preco")))
  (is (= 3.0 (resultado "media quantidade")))
  (is (= ["0.00"] (main/responder (dados-genericos) "QUERY limitar 0 | soma preco")))
  (is (= ["0"] (main/responder (dados-genericos) "QUERY limitar 0 | contar")))
  (is (= ["0.00"] (main/responder (dados-genericos) "QUERY limitar 0 | media quantidade")))
  (is (= ["(vazio)"] (main/responder (dados-genericos) "QUERY limitar 0")))
  (is (= ["(vazio)"] (main/responder (dados-genericos) "QUERY limitar 0 | agrupar por item com contar")))
  (is (= ["1.50; 1" "8.00; 2"]
         (main/responder (dados-genericos) "QUERY agrupar por preco com contar"))))

(deftest invalidas-nao-tocam-registros
  (let [dados {:esquema [[:n :inteiro] [:s :texto]]
               :registros (lazy-seq (throw (ex-info "registros foram acessados" {})))}
        textos ["onde inexistente = 1" "ordenar por inexistente"
                "selecionar inexistente" "soma s" "media s"
                "agrupar por s com soma s" "onde n = \"1\""
                "onde s = 1" "onde n = 1 ou inexistente = 0"
                "limitar 0 | onde inexistente = 1"]]
    (reduce (fn [_ texto]
              (testing texto
                (let [r (core/executar (core/analisar texto) dados)]
                  (is (seq (:erros r)))
                  (is (not-any? #(str/includes? % "registros foram acessados") (:erros r))))))
            nil textos)))

(deftest builder-invalido
  (let [c (core/consulta) dados (dados-genericos)
        casos [(core/limitar c -1) (core/limitar c 1.5)
               (core/ordenar-por c :item :outra)
               (core/selecionar c []) (core/selecionar c [:ausente])
               (core/limitar (core/selecionar c [:item]) 1)
               (core/contar (core/selecionar c [:item]))
               (core/onde (core/contar c) [:= :item "A"])
               (core/limitar (core/agrupar-por c :item [:contar]) 1)
               {:estagios [[:limitar]]}
               {:estagios [[:onde [:e [:= :item "A"]]]]}
               {:estagios [[:contar :extra]]}
               {:estagios '()} {}]]
    (reduce (fn [_ ast] (is (seq (:erros (core/executar ast dados))))) nil casos)))

(deftest nos-desconhecidos-nomeiam-o-tipo
  (let [dados (dados-genericos)]
    (is (thrown-with-msg? IllegalArgumentException #":voar"
                         (core/executar {:estagios [[:voar 3]]} dados)))
    (is (thrown-with-msg? IllegalArgumentException #":contem"
                         (core/executar {:estagios [[:onde [:contem :item "A"]]]} dados)))
    (is (thrown-with-msg? IllegalArgumentException #":minimo"
                         (core/executar {:estagios [[:agrupar-por :item [:minimo :preco]]]} dados)))))

(deftest execucao-preguicosa
  (let [dados {:esquema [[:n :inteiro]] :registros (map (fn [n] {:n n}) (range))}
        c (-> (core/consulta) (core/onde [:> :n 1000]) (core/limitar 3))]
    (is (= [{:n 1001} {:n 1002} {:n 1003}] (vec (core/executar c dados))))
    (is (= [{:n 1001} {:n 1002} {:n 1003}]
           (vec (core/executar (core/selecionar c [:n]) dados)))))
  (let [dados {:esquema [[:n :inteiro]]
               :registros (lazy-seq (throw (ex-info "nao deve realizar" {})))}]
    (is (empty? (core/executar (core/limitar (core/consulta) 0) dados))))
  (let [dados {:esquema [[:n :inteiro]]
               :registros (concat [{:n 1} {:n 2} {:n 3}]
                                  (lazy-seq (throw (ex-info "leu alem do limite" {}))))}]
    (is (= [{:n 1} {:n 2}] (vec (core/executar (core/limitar (core/consulta) 2) dados))))))

(deftest compilacao-so-antes-de-executar
  (let [f (compilador/compilar (core/analisar "onde quantidade > 2 | limitar 1")
                              (:esquema (dados-genericos)))]
    (is (fn? f))
    (is (= ["A"] (mapv :item (f (:registros (dados-genericos))))))
    (is (= [{:quantidade 9}] (vec (f [{:quantidade 1} {:quantidade 9}]))))))

(deftest erros-de-sintaxe-sao-ex-info
  (reduce (fn [_ texto]
            (testing texto
              (is (thrown? clojure.lang.ExceptionInfo (core/analisar texto)))))
          nil ["|" "limitar -1" "limitar 2.0" "limitar 1x" "limitar +"
               "limitar 1 |" "limitar 1 || contar" "selecionar"
               "selecionar item," "selecionar ,item" "contar extra"
               "onde item = \"sem fechar" "onde quantidade == 1"
               "onde quantidade > 1)" "onde ()" "onde nao" "onde quantidade > @"
               "ordenar por item asc" "agrupar por item com minimo preco"
               "contar | soma preco" nil]))

(deftest csv-generico-e-validacao
  (let [dados (core/ler-csv "\uFEFFz:inteiro;a:texto;b:decimal\r\n-7;texto;2.25\r\n")]
    (is (= [[:z :inteiro] [:a :texto] [:b :decimal]] (:esquema dados)))
    (is (= [{:z -7 :a "texto" :b 2.25}] (vec (:registros dados)))))
  (is (empty? (:registros (core/ler-csv "a:inteiro\n"))))
  (is (= 9223372036854775809N
         (core/executar (core/analisar "soma n")
                       (core/ler-csv "n:inteiro\n9223372036854775808\n1\n"))))
  (reduce (fn [_ csv]
            (is (thrown? clojure.lang.ExceptionInfo
                         (doall (:registros (core/ler-csv csv))))))
          nil ["" "nome" "a:outro" "a:inteiro;a:texto"
               "a:inteiro\nx\n" "a:inteiro\n1;2\n" "a:inteiro\n\n"
               "a:texto\n \n" "a:decimal\nNaN\n" "a:decimal\nInfinity\n"
               "a:inteiro\n1.0\n" "a:inteiro;\n1;2\n"]))

(deftest terminal-eof-exit-e-recuperacao
  (let [saida (with-in-str "\nquery contar\nQUERY voar\nQUERY contar\nEXIT\nQUERY contar\n"
                (with-out-str (main/-main "dados/filmes.csv")))]
    (is (= 3 (count (re-seq #"ERRO:" saida))))
    (is (= 1 (count (re-seq #"> 8\n" saida))))
    (is (not (str/includes? saida "Exception"))))
  (is (= "> 8\n> " (with-in-str "QUERY contar\n"
                      (with-out-str (main/-main "dados/filmes.csv")))))
  (is (= "> " (with-in-str "" (with-out-str (main/-main "dados/filmes.csv")))))
  (is (str/starts-with? (with-out-str (main/-main)) "ERRO:"))
  (is (str/starts-with? (with-out-str (main/-main "arquivo-inexistente.csv")) "ERRO:")))

(deftest formatacao-independe-de-localidade
  (is (= "8.60" (main/formatar-valor :decimal 8.6)))
  (is (= "2002" (main/formatar-valor :inteiro 2002)))
  (is (= "abc" (main/formatar-valor :texto "abc"))))

(defn -main [& _]
  (let [resumo (run-tests 'consultas.main-test)]
    (when (pos? (+ (:fail resumo) (:error resumo)))
      (throw (ex-info "testes falharam" resumo)))))
