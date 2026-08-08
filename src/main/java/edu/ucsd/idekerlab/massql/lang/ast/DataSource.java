package edu.ucsd.idekerlab.massql.lang.ast;

/** {@code MS1DATA} or {@code MS2DATA}. Determines both which peak table is queried and
 *  which result shape is emitted (Tech_Step10 §5). */
public enum DataSource {
    MS1DATA,
    MS2DATA
}
