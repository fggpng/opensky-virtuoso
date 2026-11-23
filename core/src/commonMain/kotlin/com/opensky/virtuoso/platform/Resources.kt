package com.opensky.virtuoso.platform

/**
 * Texto del catálogo de cielo profundo (catalogs/deepsky.tsv, derivado de OpenNGC).
 * En JVM/Android se lee de los recursos del classpath; en iOS va incrustado en el framework
 * (lo genera Gradle a partir del mismo archivo).
 */
internal expect fun deepSkyCatalogText(): String
