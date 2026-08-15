package org.cytoscape.massql.result;

import com.google.gson.annotations.SerializedName;

/**
 * One row of {@code scaninfo} output. Component order is the serialized key order.
 *
 * <p>Boxed throughout: {@code null} distinguishes "not recorded" from a genuine zero, which
 * {@code rt} is the case for — {@code 0.0} is a real retention time.
 */
public record ScanInfoResult(
        @SerializedName("scan") Integer scan,
        @SerializedName("precmz") Double precmz,
        @SerializedName("ms1scan") Integer ms1scan,
        @SerializedName("rt") Double rt,
        @SerializedName("charge") Integer charge,
        @SerializedName("tic") Double tic,
        @SerializedName("mslevel") Integer mslevel,
        @SerializedName("base_peak_i") Double basePeakI,
        @SerializedName("base_peak_mz") Double basePeakMz,
        @SerializedName("ms1_i") Double ms1I,
        @SerializedName("ms1_precmz") Double ms1Precmz,
        @SerializedName("ms1_base_peak_i") Double ms1BasePeakI) {}
