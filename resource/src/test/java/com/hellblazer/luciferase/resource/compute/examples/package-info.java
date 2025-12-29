/**
 * Runnable examples demonstrating GPU compute usage.
 *
 * <h2>Examples</h2>
 * <ul>
 *   <li>{@link VectorMathExample} - Built-in vector operations</li>
 *   <li>{@link CustomKernelExample} - Writing custom OpenCL kernels</li>
 *   <li>{@link PerformanceExample} - GPU vs CPU timing comparison</li>
 *   <li>{@link LowLevelExample} - Direct buffer and kernel control</li>
 * </ul>
 *
 * <h2>Running</h2>
 * <pre>
 * # Run a specific example
 * mvn exec:java -Dexec.mainClass="...examples.VectorMathExample" -pl resource
 *
 * # Or run as test
 * mvn test -Dtest=VectorMathExample -pl resource
 * </pre>
 */
package com.hellblazer.luciferase.resource.compute.examples;
