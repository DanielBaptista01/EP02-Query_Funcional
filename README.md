Daniel Santos Baptista e Isaias Maia de Oliveira

# EP02 — Query builder funcional em Clojure

Motor de consultas sobre CSV com esquema declarado no cabeçalho. Implementa o [enunciado da EP02](https://github.com/celsocrivelaro/senac-paradigmas/blob/main/eps/ep02/enunciado.md) em três camadas: texto → AST imutável → função composta. O motor não possui campos de domínio fixos.

## Como executar

Requisitos: Java 17 ou superior e **Clojure CLI** com suporte a `-M`. Execute na raiz do repositório:

```sh
clojure -M:run dados/filmes.csv
```

O projeto usa Clojure 1.12.0, sem bibliotecas externas. Na primeira execução, o CLI baixa o próprio Clojure e suas dependências de distribuição.

```text
> QUERY onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
Cidade de Deus; 8.60
Ainda Estou Aqui; 8.20
Central do Brasil; 8.00
> QUERY onde genero = "drama" | media duracao
127.40
> EXIT
```

`QUERY` sozinho devolve todos os registros. `EXIT` ou EOF encerra. Os comandos são maiúsculos; os elementos da consulta são minúsculos. Entradas inválidas produzem uma linha `ERRO:` e o terminal continua. Registros ou grupos vazios imprimem `(vazio)`; agregações vazias devolvem zero.

Outro CSV funciona substituindo o caminho. Seu cabeçalho deve declarar `campo:tipo`, separado por `;`, com tipos `inteiro`, `decimal` ou `texto`. Todos os campos devem estar preenchidos. CSV não tem aspas nem escapes. Campos de texto preservam seus espaços; números e declarações toleram espaços nas extremidades. O leitor aceita UTF-8 com BOM e quebras LF ou CRLF. Cabeçalhos inválidos, campos duplicados, valores ausentes e linhas com quantidade incorreta de campos são recusados.

## Forma exata da AST

A consulta é um mapa com a chave `:estagios`, contendo um **vetor ordenado de nós**. A consulta vazia é `{:estagios []}`. Todos os elementos, inclusive agregações finais, ficam nesse vetor; não existe chave `:terminal`.

| Elemento | Nó |
|---|---|
| Filtro | `[:onde expr]` |
| Ordenação | `[:ordenar-por :campo :asc]` ou `[:ordenar-por :campo :desc]` |
| Limite | `[:limitar n]` |
| Projeção final | `[:selecionar [:campo1 :campo2]]` |
| Contagem final | `[:contar]` |
| Soma final | `[:soma :campo]` |
| Média final | `[:media :campo]` |
| Agrupamento final | `[:agrupar-por :campo agregacao]` |

`agregacao` é `[:contar]`, `[:soma :campo]` ou `[:media :campo]`. O resultado do agrupamento é uma sequência de pares `[chave valor]`, ordenada crescentemente pela chave.

| Expressão | Nó |
|---|---|
| Comparação | `[operador :campo literal]` |
| Conjunção | `[:e expr1 expr2]` |
| Disjunção | `[:ou expr1 expr2]` |
| Negação | `[:nao expr]` |

Operadores: `:=`, `:!=`, `:<`, `:<=`, `:>` e `:>=`. Literais são números ou strings. O parser produz inteiros de precisão arbitrária e decimais `Double`; inteiros usuais do builder são iguais aos inteiros analisados por `=`. Texto usa aspas duplas sem escape. Identificadores admitem letras Unicode, números depois do primeiro caractere, `_` e `-`.

Exemplo:

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:selecionar [:titulo :nota]]]}
```

A AST não contém funções ou objetos mutáveis. Pode ser impressa, comparada ou construída à mão. Para testar um nó desconhecido:

```clojure
(executar {:estagios [[:voar 3]]} dados)
;; IllegalArgumentException: No matching clause: :voar
```

Esse erro é intencional: os despachos `case` do compilador não têm ramo padrão. A interface o converte em `ERRO:`. Consultas com nós conhecidos, mas inválidas em estrutura ou esquema, devolvem `{:erros ["motivo"]}` por `executar`.

## API do builder

As funções públicas estão em `consultas.core`. O builder acrescenta dados e devolve outra consulta; a original permanece intacta. `executar` verifica a validade, inclusive a proibição de elementos depois de projeção ou agregação final.

```clojure
(require '[consultas.core :refer :all])

(def base (consulta))
(def dramas (onde base [:= :genero "drama"]))
(def dois (limitar dramas 2))
(def tres (limitar dramas 3))

(= dois (analisar "onde genero = \"drama\" | limitar 2"))
;; => true

;; dados e fornecido pelo chamador:
;; {:esquema [[:campo :tipo] ...] :registros sequencia-de-mapas}
(executar dois dados)
(executar tres dados)
```

Além de `consulta`, `onde`, `selecionar`, `ordenar-por`, `limitar`, `analisar` e `executar`, a API oferece `contar`, `soma`, `media`, `agrupar-por` e `ler-csv`. `ler-csv` recebe **texto**, sem ler arquivos. `analisar` recebe texto sem `QUERY` e sinaliza erros de sintaxe com `ex-info`.

## Namespaces

| Namespace | Papel |
|---|---|
| `consultas.modelo` | Consulta vazia, inclusão imutável de nós e identificação de elementos finais. |
| `consultas.sintaxe` | Tokenização e análise recursiva; recebe tokens e devolve `[no tokens-restantes]`. Implementa precedência `nao`, `e`, `ou` e parênteses. |
| `consultas.compilador` | Valida e compila cada nó contra o esquema, constrói predicados e compõe o pipeline. Não acessa registros durante a compilação. |
| `consultas.core` | API pública do builder, conversão pura de CSV e execução. |
| `consultas.main` | Leitura do arquivo, terminal com `loop`/`recur`, formatação e impressão. Único namespace de aplicação com I/O. |
| `consultas.main-test` | Bateria automatizada, testes de contrato e entrada dos testes. |

## Critérios funcionais

- Cada nó é conferido e compilado **uma vez**. O pipeline só recebe os registros depois da conferência da consulta inteira. Os predicados não consultam a AST durante a execução.
- O pipeline é um `reduce` a partir de `identity`, compondo `(comp estagio funcao)` para respeitar a ordem do texto.
- Comparações, filtros, limite, ordenação e projeção usam `partial` para fixar argumentos.
- Filtros, projeções e limites preservam sequências preguiçosas. Ordenação e agrupamento consomem a entrada; terminais a dobram com `reduce`. Limites anteriores são respeitados.
- A ordenação é estável, inclusive em ordem decrescente. Grupos reaproveitam as funções de agregação dos terminais.
- Inteiros são somados com promoção automática de precisão. Comparações usam `compare`, permitindo igualdade entre inteiro e decimal. Decimais e médias saem com duas casas e ponto por `Locale/ROOT`.
- A aplicação utiliza dados persistentes e funções puras; efeitos ficam na entrada e saída. Não executa código contido na consulta.

## Testes

```sh
clojure -M:test
```

A suíte usa somente `clojure.test`, incluído no Clojure. Lê `casos_teste_ep02.txt` e confere automaticamente seus **48 casos**, incluindo mensagens de erro, campos citados e `EXIT`. Os testes adicionais verificam:

- derivação independente de consultas e igualdade entre parser e builder;
- os seis operadores, precedência, parênteses e negações;
- esquema diferente do CSV de filmes e ordem dos campos na saída;
- estabilidade da ordenação e ordem dos estágios;
- agregações, grupos e resultados vazios;
- recusa de consultas inválidas sem acessar registros;
- identificação de estágios, operadores e agregações desconhecidos;
- filtro e limite sobre registros infinitos, sem leitura além do limite;
- reutilização de funções compiladas;
- validação de CSV, inteiros grandes, EOF e recuperação do terminal.

Resultado da verificação: **16 testes, 209 asserções, zero falhas e zero erros**, incluindo execução sob localidade brasileira. O comando `-M:run` também é verificado. A busca estática das restrições encontra `doseq` somente em `consultas.main`, onde o enunciado permite seu uso.

## Fontes e uso de IA

`dados/filmes.csv` e `casos_teste_ep02.txt` são materiais fornecidos pelo professor Celso Crivelaro no [repositório da disciplina](https://github.com/celsocrivelaro/senac-paradigmas/tree/main/eps/ep02). Os critérios e a organização derivam do enunciado e de seu [anexo de Clojure](https://github.com/celsocrivelaro/senac-paradigmas/blob/main/eps/ep02/anexo-clojure.md).

Esta implementação, documentação e testes foram produzidos com assistência de **OpenAI Codex**, a pedido do integrante identificado no início deste arquivo. Esta declaração não afirma revisão humana já realizada. A revisão, a compreensão do código e a verificação de sua admissibilidade para entrega permanecem com o integrante.

O enunciado referencia a [Política de uso de ferramentas generativas de IA](https://crivelaro.notion.site/Pol-tica-de-uso-de-ferramentas-generativas-de-IA-1b53bb4e12a54b4aa06eaa02e62192f4?pvs=74) e a [Política antiplágio](https://crivelaro.notion.site/Pol-tica-antipl-gio-5187d7b1ab514bfb8424ac0fcfb59dba?pvs=74). Essas páginas não puderam ser lidas no ambiente de implementação. Portanto, este registro descreve a assistência utilizada, mas não certifica conformidade com condições adicionais dessas políticas.
