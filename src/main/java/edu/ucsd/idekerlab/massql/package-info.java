/**
 * Pure-Java MassQL SDK, {@code scaninfo} subset.
 *
 * <p>Public entry points live here; everything else is implementation detail that may
 * churn. See {@code docs/SDK.md} for how to obtain and build it, and this package's javadoc
 * for the surface {@code massql-app} codes
 * against, and {@code DEPENDENCY_POLICY.md} for the constraints every package must respect.
 *
 * <p><b>This artifact must never compile against Cytoscape.</b> That compile-time firewall
 * is the only thing that reliably keeps {@code org.cytoscape} imports out of engine code.
 */
package edu.ucsd.idekerlab.massql;
