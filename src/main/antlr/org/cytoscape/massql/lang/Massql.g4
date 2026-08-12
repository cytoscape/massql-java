/*
 * MassQL grammar, translated from MassQL's Lark EBNF grammar (165 lines).
 *
 * TRANSLATION PHILOSOPHY: this grammar is DELIBERATELY PERMISSIVE. It admits the whole
 * MassQL language, including everything out of scope for v1, so that AstBuilder can
 * reject each construct BY NAME (MassqlParseException.construct()). A grammar that simply
 * omitted the unsupported constructs would report "syntax error at 'formula'" instead of
 * "formula() is not supported in this version", and 31 of the 46 reference parses need a
 * named rejection.
 *
 * Divergences from the Lark source are listed in docs/internals/GRAMMAR_NOTES.md. The two that
 * affect parsing:
 *   1. FLOAT carries NO sign; unary +/- is a parser rule. Lark's
 *      floating: /[-+]?(...)/ works under Earley's contextual lexing, but ANTLR's
 *      maximal-munch lexer would tokenise "X+2" as VARIABLE FLOAT(+2) rather than
 *      VARIABLE PLUS FLOAT(2), breaking every additive expression in the corpus.
 *   2. Compound literals like "(min=" are split into LPAREN + MIN_EQ.
 *
 * NOTE: this file must stay under src/main/antlr4/. Some build setups override
 * <filtering>true</filtering> on resources and would silently corrupt ${...} here.
 */
grammar Massql;

// ============================ PARSER ============================

/** Entry point. EOF is mandatory: without it ANTLR happily parses a prefix and ignores
 *  trailing garbage, so "QUERY scaninfo(MS2DATA) junk" would succeed.
 *
 *  The anchor is split from `query` because a nested sub-query (`MS2PREC=(QUERY ...)`)
 *  must be able to match WITHOUT EOF. Referencing an EOF-anchored rule from inside
 *  parentheses makes the subquery alternative unmatchable, which silently downgrades its
 *  named rejection to a generic syntax error. */
statement : query EOF ;

query
    : queryKeyword queryType whereClause? filterClause?
    ;

whereClause  : whereKeyword whereConditionList ;
filterClause : FILTER filterConditionList ;

/* Lark writes `wherefullcondition+`, i.e. conditions may be separated by AND *or* by
   nothing at all (whitespace only). Both appear in the corpus, so accept both. */
whereConditionList  : fullCondition (AND_KW? fullCondition)* ;
filterConditionList : fullCondition (AND_KW? fullCondition)* ;

fullCondition : condition (COLON qualifier)* ;

queryType
    : dataType                                            # bareDataType
    | function LPAREN dataType RPAREN                     # functionDataType
    | function LPAREN dataType COMMA TOLERANCE_PARAM EQ floating RPAREN  # functionDataTypeWithTolerance
    ;

dataType : MS1DATA | MS2DATA ;

function
    : SCANINFO | SCANNUM | SCANSUM | SCANRANGESUM | SCANMZ | SCANMAXINT
    ;

condition
    : conditionField EQ numericalExpression                        # valueCondition
    | conditionField EQ LPAREN query RPAREN                        # subqueryCondition
    | conditionField EQ LPAREN numericalExpressionWithOr RPAREN    # orListCondition
    | conditionField EQ ANY                                        # wildcardCondition
    | POLARITY EQ polarity                                         # polarityCond
    | VARIABLE EQ rangeFunction LPAREN MIN_EQ numericalExpression COMMA MAX_EQ numericalExpression RPAREN  # variableRangeCondition
    | MOBILITY EQ RANGE_FN LPAREN MIN_EQ numericalExpression COMMA MAX_EQ numericalExpression RPAREN       # mobilityCond
    ;

/* MS2MZ is an alias for MS2PROD; the alias is collapsed in AstBuilder, not here, so the
   grammar stays a faithful mirror of the source. */
conditionField
    : MS2PROD | MS2MZ | MS2PREC | MS2NL | MS1MZ
    | RTMIN | RTMAX | SCANMIN | SCANMAX | CHARGE
    ;

polarity : POSITIVE | NEGATIVE ;

rangeFunction : RANGE_FN | MASSDEFECT_FN ;

qualifier
    : qualifierField EQ numericalExpression      # qualifierEq
    | qualifierField GT numericalExpression      # qualifierGt
    | qualifierField LT numericalExpression      # qualifierLt
    | INTENSITYMATCHREFERENCE                    # qualifierIntensityMatchReference
    | EXCLUDED                                   # qualifierExcluded
    | MASSDEFECT EQ MASSDEFECT_FN LPAREN MIN_EQ numericalExpression COMMA MAX_EQ numericalExpression RPAREN   # qualifierMassDefect
    | cardinality EQ RANGE_FN LPAREN MIN_EQ numericalExpression COMMA MAX_EQ numericalExpression RPAREN       # qualifierCardinality
    | OTHERSCAN EQ RTRANGE_FN LPAREN LEFT_EQ numericalExpression COMMA RIGHT_EQ numericalExpression RPAREN    # qualifierOtherScan
    ;

qualifierField
    : TOLERANCEMZ | TOLERANCEPPM
    | INTENSITYPERCENT | INTENSITYTICPERCENT | INTENSITYVALUE
    | INTENSITYMATCH | INTENSITYMATCHPERCENT
    ;

cardinality : CARDINALITY | MATCHCOUNT ;

numericalExpressionWithOr : numericalExpression (OR_KW numericalExpression)* ;

/* Direct left recursion, as in the source. ANTLR4 rewrites it and derives precedence
   from ALTERNATIVE ORDER, so the order below is load-bearing: multiply/divide bind
   tighter than plus/minus because they appear first. Do not reorder while tidying. */
numericalExpression
    : numericalExpression (MULTIPLY | DIVIDE) numericalExpression   # mulDiv
    | numericalExpression (PLUS | MINUS) numericalExpression        # addSub
    | (PLUS | MINUS) numericalExpression                            # unary
    | LPAREN numericalExpression RPAREN                             # paren
    | floating                                                      # literal
    | VARIABLE                                                      # variableRef
    | FORMULA_OPEN formulaBody RPAREN                               # formulaCall
    | AMINOACIDDELTA_OPEN formulaBody RPAREN                        # aminoAcidDeltaCall
    | PEPTIDE_OPEN formulaBody COMMA CHARGE_EQ floating COMMA ION_EQ formulaBody RPAREN  # peptideCall
    ;

/* Loose on purpose. Lark distinguishes a molecule formula from the variable X using
   Earley's contextual lexing; ANTLR's DFA lexer cannot, so "formula(X)" lexes X as
   VARIABLE and "formula(Fe)" lexes Fe as IDENT. Since all three of these functions are
   rejected by AstBuilder anyway, accepting either token here buys a named rejection
   without needing lexer modes. Lexer modes remain the documented fallback if these are
   ever brought in scope (docs/internals/GRAMMAR_NOTES.md). */
formulaBody : (IDENT | VARIABLE | floating)+ ;

floating : FLOAT ;

/* Case variants are enumerated literally rather than handled with a case-insensitive
   lexer, because MassQL's casing is ASYMMETRIC: FILTER and OR have no lowercase form,
   and condition/qualifier names are strictly uppercase. A case-insensitive lexer would
   accept "filter" and "or", which must reject. */
queryKeyword : QUERY_KW ;
whereKeyword : WHERE_KW ;

// ============================ LEXER ============================

QUERY_KW : 'QUERY' | 'query' | 'Query' ;
WHERE_KW : 'WHERE' | 'where' | 'Where' ;
AND_KW   : 'AND'   | 'and'   | 'And'   ;
MS1DATA  : 'MS1DATA' | 'ms1data' | 'Ms1Data' ;
MS2DATA  : 'MS2DATA' | 'ms2data' | 'Ms2Data' ;
POSITIVE : 'POSITIVE' | 'positive' | 'Positive' ;
NEGATIVE : 'NEGATIVE' | 'negative' | 'Negative' ;

// No case variants in the source. Lowercase 'filter' / 'or' MUST fail to parse.
FILTER : 'FILTER' ;
OR_KW  : 'OR' ;

// Functions (lowercase in the source).
SCANINFO     : 'scaninfo' ;
SCANNUM      : 'scannum' ;
SCANSUM      : 'scansum' ;
SCANRANGESUM : 'scanrangesum' ;
SCANMZ       : 'scanmz' ;
SCANMAXINT   : 'scanmaxint' ;

// Range-style helper functions (lowercase). Declared before IDENT.
RANGE_FN      : 'range' ;
MASSDEFECT_FN : 'massdefect' ;
RTRANGE_FN    : 'rtrange' ;

/* Condition fields — strictly uppercase. ANTLR's maximal munch resolves prefixes
   (longest match wins); declaration order is only the tiebreak for equal lengths. */
MS2PROD  : 'MS2PROD' ;
MS2MZ    : 'MS2MZ' ;
MS2PREC  : 'MS2PREC' ;
MS2NL    : 'MS2NL' ;
MS1MZ    : 'MS1MZ' ;
RTMIN    : 'RTMIN' ;
RTMAX    : 'RTMAX' ;
SCANMIN  : 'SCANMIN' ;
SCANMAX  : 'SCANMAX' ;
POLARITY : 'POLARITY' ;
CHARGE   : 'CHARGE' ;
MOBILITY : 'MOBILITY' ;

/* Qualifiers — strictly uppercase. INTENSITYMATCH is a prefix of both
   INTENSITYMATCHPERCENT and INTENSITYMATCHREFERENCE, and TOLERANCE is a prefix of
   TOLERANCEMZ/TOLERANCEPPM. Maximal munch resolves all of these, which is why
   TOLERANCE_PARAM below is safe. */
TOLERANCEMZ             : 'TOLERANCEMZ' ;
TOLERANCEPPM            : 'TOLERANCEPPM' ;
INTENSITYPERCENT        : 'INTENSITYPERCENT' ;
INTENSITYTICPERCENT     : 'INTENSITYTICPERCENT' ;
INTENSITYVALUE          : 'INTENSITYVALUE' ;
INTENSITYMATCHREFERENCE : 'INTENSITYMATCHREFERENCE' ;
INTENSITYMATCHPERCENT   : 'INTENSITYMATCHPERCENT' ;
INTENSITYMATCH          : 'INTENSITYMATCH' ;
MASSDEFECT              : 'MASSDEFECT' ;
EXCLUDED                : 'EXCLUDED' ;
CARDINALITY             : 'CARDINALITY' ;
MATCHCOUNT              : 'MATCHCOUNT' ;
OTHERSCAN               : 'OTHERSCAN' ;

// The scanrangesum TOLERANCE parameter (`param: "TOLERANCE"`).
TOLERANCE_PARAM : 'TOLERANCE' ;

ANY : 'ANY' ;

/* Compound literals from the source, split from their leading paren so the grammar can
   use a single LPAREN token throughout. */
MIN_EQ    : 'min='    ;
MAX_EQ    : 'max='    ;
LEFT_EQ   : 'left='   ;
RIGHT_EQ  : 'right='  ;
CHARGE_EQ : 'charge=' ;
ION_EQ    : 'ion='    ;

// Function-call literals, kept whole exactly as the source writes them.
FORMULA_OPEN         : 'formula(' ;
AMINOACIDDELTA_OPEN  : 'aminoaciddelta(' ;
PEPTIDE_OPEN         : 'peptide(' ;

/* Single character X or Y ONLY. Declared before IDENT so a bare X lexes as VARIABLE.
   A multi-char sequence like "XY" therefore lexes as IDENT, which no in-scope parser
   rule accepts — that is how the corpus's "XY must reject" case is enforced. */
VARIABLE : [XY] ;

EQ       : '=' ;
LT       : '<' ;
GT       : '>' ;
MULTIPLY : '*' ;
DIVIDE   : '/' ;
PLUS     : '+' ;
MINUS    : '-' ;
LPAREN   : '(' ;
RPAREN   : ')' ;
COMMA    : ',' ;
COLON    : ':' ;

/* Unsigned. See the header note: the sign is a parser rule, not part of the token, or
   maximal munch would swallow the '+' in "X+2".
   NO exponent form — the source regex is /[-+]?([0-9]*\.[0-9]+|[0-9]+)/, so "1e5" must
   NOT parse. It lexes as FLOAT(1) IDENT(e5) and fails in the parser. */
FLOAT : [0-9]* '.' [0-9]+ | [0-9]+ ;

// Declared last so every keyword above wins.
IDENT : [A-Za-z] [A-Za-z0-9]* ;

WS : [ \t\r\n]+ -> skip ;
