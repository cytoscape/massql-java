// PLACEHOLDER GRAMMAR -- Tech_Step4 replaces this wholesale.
//
// It exists only to prove the antlr4-maven-plugin, generate-sources wiring and
// <release>17</release> work together before Step 4 starts. Do not build on it.
//
// The real grammar is a translation of massql/msql.ebnf (165 lines, Lark EBNF) at
// pinned SHA dad2a28c01e6e5132240270fc6700fbae29f1652.
//
// NOTE for Step 4: this file MUST stay under src/main/antlr4/. Cytoscape app poms set
// <filtering>true</filtering> on resources and would silently corrupt ${...} in a
// grammar placed under src/main/resources/.
grammar Massql;

placeholder : QUERY EOF ;

QUERY : 'QUERY' ;
WS    : [ \t\r\n]+ -> skip ;
