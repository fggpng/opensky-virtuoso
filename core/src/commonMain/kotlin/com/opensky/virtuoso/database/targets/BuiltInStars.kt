package com.opensky.virtuoso.database.targets

import com.opensky.virtuoso.astronomy.EquatorialCoord

/** Estrella con nombre propio (J2000/ICRS, Hipparcos). */
data class NamedStar(
    val name: String,
    val bayer: String,
    val hip: Int,
    val j2000: EquatorialCoord,
    val vMag: Double,
    val pmRaMasYr: Double,
    val pmDecMasYr: Double,
)

/** Estrella doble o múltiple observable con un reflector de 150 mm. */
data class DoubleStar(
    val name: String,
    val designation: String,
    val j2000: EquatorialCoord,
    val magA: Double,
    val magB: Double,
    /** Separación aproximada (″) y ángulo de posición (°); en binarias orbitales cambian con los años. */
    val separationArcsec: Double,
    val positionAngleDeg: Double?,
    val pmRaMasYr: Double = 0.0,
    val pmDecMasYr: Double = 0.0,
    val notes: String = "",
)

/**
 * Catálogos integrados. Coordenadas J2000 (Hipparcos). Si el Tycho-2 está instalado, las
 * estrellas con nombre se refinan automáticamente por su número HIP.
 */
object BuiltInStars {

    // nombre | Bayer | HIP | AR (h m s) | Dec (° ′ ″) | V | μα·cosδ | μδ (mas/año)
    private const val NAMED = """
Sirius|α CMa|32349|06 45 08.917|-16 42 58.02|-1.46|-546.01|-1223.07
Canopus|α Car|30438|06 23 57.110|-52 41 44.38|-0.74|19.93|23.24
Arcturus|α Boo|69673|14 15 39.672|+19 10 56.67|-0.05|-1093.39|-2000.06
Rigil Kentaurus|α Cen|71683|14 39 36.494|-60 50 02.37|-0.01|-3679.25|473.67
Vega|α Lyr|91262|18 36 56.336|+38 47 01.28|0.03|200.94|286.23
Capella|α Aur|24608|05 16 41.359|+45 59 52.77|0.08|75.25|-426.89
Rigel|β Ori|24436|05 14 32.272|-08 12 05.90|0.13|1.31|0.50
Procyon|α CMi|37279|07 39 18.119|+05 13 29.96|0.34|-714.59|-1036.80
Achernar|α Eri|7588|01 37 42.845|-57 14 12.33|0.46|87.00|-38.24
Betelgeuse|α Ori|27989|05 55 10.305|+07 24 25.43|0.42|27.54|11.30
Hadar|β Cen|68702|14 03 49.405|-60 22 22.93|0.61|-33.27|-23.16
Altair|α Aql|97649|19 50 46.999|+08 52 05.96|0.76|536.23|385.29
Acrux|α Cru|60718|12 26 35.896|-63 05 56.73|0.76|-35.83|-14.86
Aldebaran|α Tau|21421|04 35 55.239|+16 30 33.49|0.86|63.45|-188.94
Spica|α Vir|65474|13 25 11.579|-11 09 40.75|0.97|-42.35|-30.67
Antares|α Sco|80763|16 29 24.459|-26 25 55.21|1.06|-12.11|-23.30
Pollux|β Gem|37826|07 45 18.950|+28 01 34.32|1.14|-626.55|-45.80
Fomalhaut|α PsA|113368|22 57 39.046|-29 37 20.05|1.16|328.95|-164.67
Deneb|α Cyg|102098|20 41 25.915|+45 16 49.22|1.25|2.01|1.85
Mimosa|β Cru|62434|12 47 43.269|-59 41 19.58|1.25|-42.97|-16.18
Regulus|α Leo|49669|10 08 22.311|+11 58 01.95|1.40|-248.73|5.59
Adhara|ε CMa|33579|06 58 37.548|-28 58 19.51|1.50|3.24|1.33
Castor|α Gem|36850|07 34 35.863|+31 53 17.82|1.58|-191.45|-145.19
Shaula|λ Sco|85927|17 33 36.520|-37 06 13.76|1.62|-8.53|-30.80
Gacrux|γ Cru|61084|12 31 09.960|-57 06 47.57|1.64|28.23|-265.08
Bellatrix|γ Ori|25336|05 25 07.863|+06 20 58.93|1.64|-8.11|-12.88
Elnath|β Tau|25428|05 26 17.513|+28 36 26.83|1.65|22.76|-173.58
Miaplacidus|β Car|45238|09 13 11.977|-69 43 01.95|1.69|-156.47|108.95
Alnilam|ε Ori|26311|05 36 12.813|-01 12 06.91|1.69|1.44|-0.78
Alnair|α Gru|109268|22 08 13.985|-46 57 39.51|1.74|126.69|-147.47
Alnitak|ζ Ori|26727|05 40 45.527|-01 56 33.26|1.77|3.19|2.03
Alioth|ε UMa|62956|12 54 01.749|+55 57 35.36|1.77|111.91|-8.24
Dubhe|α UMa|54061|11 03 43.672|+61 45 03.72|1.79|-134.11|-34.70
Mirfak|α Per|15863|03 24 19.370|+49 51 40.25|1.79|23.75|-26.23
Kaus Australis|ε Sgr|90185|18 24 10.318|-34 23 04.62|1.79|-39.42|-124.20
Wezen|δ CMa|34444|07 08 23.485|-26 23 35.52|1.83|-3.12|3.31
Avior|ε Car|41037|08 22 30.836|-59 30 34.14|1.86|-25.52|22.72
Alkaid|η UMa|67301|13 47 32.438|+49 18 47.76|1.86|-121.17|-14.91
Sargas|θ Sco|86228|17 37 19.129|-42 59 52.18|1.86|5.99|-0.95
Menkalinan|β Aur|28360|05 59 31.723|+44 56 50.76|1.90|-56.44|-0.95
Atria|α TrA|82273|16 48 39.895|-69 01 39.76|1.91|17.99|-31.58
Alhena|γ Gem|31681|06 37 42.711|+16 23 57.41|1.92|-2.04|-66.92
Peacock|α Pav|100751|20 25 38.858|-56 44 06.32|1.94|6.90|-86.02
Polaris|α UMi|11767|02 31 49.095|+89 15 50.79|1.98|44.48|-11.85
Mirzam|β CMa|30324|06 22 41.986|-17 57 21.31|1.98|-3.23|-0.78
Alphard|α Hya|46390|09 27 35.243|-08 39 30.96|1.98|-15.23|34.37
Hamal|α Ari|9884|02 07 10.406|+23 27 44.70|2.00|188.55|-148.08
Diphda|β Cet|3419|00 43 35.371|-17 59 11.78|2.04|232.55|31.99
Nunki|σ Sgr|92855|18 55 15.926|-26 17 48.20|2.05|15.14|-53.43
Menkent|θ Cen|68933|14 06 40.947|-36 22 11.84|2.06|-519.29|-517.87
Alpheratz|α And|677|00 08 23.260|+29 05 25.55|2.06|137.46|-163.44
Mirach|β And|5447|01 09 43.924|+35 37 14.01|2.05|175.90|-112.20
Saiph|κ Ori|27366|05 47 45.389|-09 40 10.58|2.07|1.46|-1.28
Kochab|β UMi|72607|14 50 42.326|+74 09 19.81|2.08|-32.61|11.42
Rasalhague|α Oph|86032|17 34 56.069|+12 33 36.13|2.07|108.07|-221.57
Algol|β Per|14576|03 08 10.132|+40 57 20.33|2.12|2.99|-1.66
Almach|γ And|9640|02 03 53.953|+42 19 47.01|2.10|43.08|-50.85
Denebola|β Leo|57632|11 49 03.578|+14 34 19.41|2.14|-497.68|-114.67
Navi|γ Cas|4427|00 56 42.532|+60 43 00.27|2.15|25.65|-3.82
Muhlifain|γ Cen|61932|12 41 31.040|-48 57 35.54|2.20|-187.28|-1.20
Naos|ζ Pup|39429|08 03 35.047|-40 00 11.33|2.21|-30.82|16.77
Aspidiske|ι Car|45556|09 17 05.407|-59 16 30.84|2.21|-19.03|13.11
Alphecca|α CrB|76267|15 34 41.268|+26 42 52.89|2.22|120.27|-89.58
Suhail|λ Vel|44816|09 07 59.758|-43 25 57.33|2.21|-23.21|14.28
Mizar|ζ UMa|65378|13 23 55.540|+54 55 31.27|2.23|121.23|-22.01
Sadr|γ Cyg|100453|20 22 13.702|+40 15 24.04|2.23|2.43|-0.93
Schedar|α Cas|3179|00 40 30.441|+56 32 14.39|2.24|50.36|-32.17
Eltanin|γ Dra|87833|17 56 36.370|+51 29 20.02|2.24|-8.48|-22.79
Mintaka|δ Ori|25930|05 32 00.400|-00 17 56.74|2.23|0.64|-0.69
Caph|β Cas|746|00 09 10.685|+59 08 59.21|2.28|523.50|-179.77
Dschubba|δ Sco|78401|16 00 20.005|-22 37 18.14|2.29|-8.67|-36.90
Larawag|ε Sco|82396|16 50 09.811|-34 17 35.63|2.29|-611.83|-255.87
Merak|β UMa|53910|11 01 50.477|+56 22 56.73|2.37|81.43|33.49
Izar|ε Boo|72105|14 44 59.217|+27 04 27.21|2.37|-50.95|21.07
Enif|ε Peg|107315|21 44 11.156|+09 52 30.04|2.38|26.92|0.44
Ankaa|α Phe|2081|00 26 17.051|-42 18 21.55|2.40|233.05|-356.30
Phecda|γ UMa|58001|11 53 49.847|+53 41 41.14|2.43|107.68|11.01
Sabik|η Oph|84012|17 10 22.687|-15 43 29.66|2.43|40.13|99.17
Scheat|β Peg|113881|23 03 46.458|+28 04 58.03|2.42|187.65|136.93
Alderamin|α Cep|105199|21 18 34.772|+62 35 08.07|2.45|150.55|49.09
Aludra|η CMa|35904|07 24 05.703|-29 18 11.18|2.45|-3.76|6.66
Markab|α Peg|113963|23 04 45.653|+15 12 18.96|2.48|60.40|-41.30
Aljanah|ε Cyg|102488|20 46 12.683|+33 58 12.93|2.48|355.66|330.60
Menkar|α Cet|14135|03 02 16.773|+04 05 23.06|2.54|-10.41|-76.85
Zosma|δ Leo|54872|11 14 06.501|+20 31 25.39|2.56|143.13|-129.83
Arneb|α Lep|25985|05 32 43.816|-17 49 20.24|2.58|3.56|1.18
Gienah|γ Crv|59803|12 15 48.371|-17 32 30.95|2.58|-159.58|22.31
Ascella|ζ Sgr|93506|19 02 36.719|-29 52 48.38|2.60|13.87|1.62
Zubeneschamali|β Lib|74785|15 17 00.414|-09 22 58.49|2.61|-96.39|-20.76
Unukalhai|α Ser|77070|15 44 16.074|+06 25 32.26|2.63|133.84|44.81
Sheratan|β Ari|8903|01 54 38.411|+20 48 28.91|2.64|96.32|-108.80
Ruchbah|δ Cas|6686|01 25 49.000|+60 14 07.02|2.68|297.24|-49.50
Muphrid|η Boo|67927|13 54 41.079|+18 23 51.79|2.68|-60.95|-356.29
Kaus Media|δ Sgr|89931|18 20 59.643|-29 49 41.17|2.70|29.14|-25.61
Tarazed|γ Aql|97278|19 46 15.580|+10 36 47.74|2.72|15.72|-3.08
Porrima|γ Vir|61941|12 41 39.643|-01 26 57.74|2.74|-616.66|60.66
Kornephoros|β Her|80816|16 30 13.200|+21 29 22.61|2.78|-98.43|-14.49
Vindemiatrix|ε Vir|63608|13 02 10.597|+10 57 32.94|2.79|-273.80|19.96
Kaus Borealis|λ Sgr|90496|18 27 58.241|-25 25 18.11|2.82|-44.81|-186.29
Algenib|γ Peg|1067|00 13 14.151|+15 11 00.94|2.83|1.98|-9.28
Deneb Algedi|δ Cap|107556|21 47 02.444|-16 07 38.23|2.85|261.67|-296.23
Alcyone|η Tau|17702|03 47 29.077|+24 06 18.49|2.87|19.34|-43.67
Fawaris|δ Cyg|97165|19 44 58.479|+45 07 50.91|2.87|43.22|48.44
Gomeisa|β CMi|36188|07 27 09.042|+08 17 21.54|2.89|-50.28|-38.45
Cor Caroli|α² CVn|63125|12 56 01.667|+38 19 06.17|2.89|-233.43|54.98
Sadalsuud|β Aqr|106278|21 31 33.534|-05 34 16.23|2.90|22.07|-5.38
Sadalmelik|α Aqr|109074|22 05 47.036|-00 19 11.46|2.94|18.25|-9.39
Alnasl|γ² Sgr|88635|18 05 48.488|-30 25 26.72|2.98|-55.66|-181.53
Mira|ο Cet|10826|02 19 20.793|-02 58 39.50|3.04|10.33|-239.48
Albireo|β¹ Cyg|95947|19 30 43.281|+27 57 34.85|3.08|-7.09|-5.63
Rasalgethi|α¹ Her|84345|17 14 38.858|+14 23 25.23|3.10|-7.32|36.07
Acamar|θ¹ Eri|13847|02 58 15.675|-40 18 16.85|3.20|-52.89|21.98
Sulafat|γ Lyr|93194|18 58 56.622|+32 41 22.40|3.25|-2.76|1.10
Thuban|α Dra|68756|14 04 23.350|+64 22 33.06|3.65|-56.52|17.19
Algieba|γ¹ Leo|50583|10 19 58.355|+19 50 29.36|2.37|310.77|-152.88
"""

    // nombre | designación | AR | Dec | mA | mB | sep″ | PA° | μα·cosδ | μδ | notas
    private const val DOUBLES = """
Albireo|β Cyg|19 30 43.281|+27 57 34.85|3.1|5.1|34.4|54|0|0|Contraste dorado/azul
Mizar y Alcor|ζ UMa|13 23 55.540|+54 55 31.27|2.3|3.9|14.4|152|121|-22|Alcor a 11.8′
Doble Doble N|ε¹ Lyr|18 44 20.34|+39 40 12.4|5.0|6.1|2.2|347|0|0|Requiere buen seeing
Doble Doble S|ε² Lyr|18 44 22.78|+39 36 45.8|5.2|5.5|2.4|76|0|0|Requiere buen seeing
Almach|γ And|02 03 53.953|+42 19 47.01|2.3|5.0|9.6|63|43|-51|Naranja y azul
Algieba|γ Leo|10 19 58.355|+19 50 29.36|2.4|3.6|4.7|126|311|-153|Binaria de 510 años
Izar|ε Boo|14 44 59.217|+27 04 27.21|2.6|4.8|2.9|342|-51|21|Pulcherrima
Castor|α Gem|07 34 35.863|+31 53 17.82|1.9|3.0|5.5|50|-191|-145|Binaria orbital; separación variable
Mesarthim|γ Ari|01 53 31.81|+19 17 37.9|4.5|4.6|7.5|1|0|0|Pareja gemela blanca
Rigel|β Ori|05 14 32.272|-08 12 05.90|0.1|6.8|9.5|204|0|0|Compañera difícil por el brillo
Alnitak|ζ Ori|05 40 45.527|-01 56 33.26|1.9|3.7|2.3|167|0|0|Cerca de la nebulosa Flama
Trapecio|θ¹ Ori|05 35 16.46|-05 23 23.2|5.1|6.7|13.0||0|0|Cuatro estrellas en M42
Sigma Orionis|σ Ori|05 38 44.77|-02 36 00.3|3.8|6.6|12.9|84|0|0|Sistema múltiple
Achird|η Cas|00 49 06.29|+57 48 54.7|3.5|7.4|13.4|324|1087|-560|Amarilla y rojiza
Iota Cancri|ι Cnc|08 46 41.82|+28 45 35.6|4.0|6.6|30.5|307|0|0|Albireo de primavera
61 Cygni|61 Cyg|21 06 53.94|+38 44 57.9|5.2|6.1|31.6|152|4164|3250|Estrella de Bessel (gran movimiento propio)
Gamma Delphini|γ Del|20 46 39.50|+16 07 27.5|4.4|5.0|8.9|265|0|0|Dorada y verdosa
Cor Caroli|α CVn|12 56 01.667|+38 19 06.17|2.9|5.6|19.3|229|-233|55|
Porrima|γ Vir|12 41 39.643|-01 26 57.74|3.5|3.5|3.0||-617|61|Binaria de 169 años; separación creciente
Rasalgethi|α Her|17 14 38.858|+14 23 25.23|3.5|5.4|4.6|103|-7|36|Roja y verde
Acrab|β Sco|16 05 26.23|-19 48 19.6|2.6|4.9|13.6|20|0|0|
Epsilon Monocerotis|ε Mon|06 23 46.08|+04 35 34.3|4.4|6.6|12.1|27|0|0|
Beta Monocerotis|β Mon|06 28 48.98|-07 01 58.1|4.6|5.0|7.1|132|0|0|Triple espectacular
Albireo de invierno|h3945 CMa|07 16 36.83|-23 18 56.1|4.8|6.8|26.8|52|0|0|Naranja y azul
Gamma Leporis|γ Lep|05 44 27.79|-22 26 54.2|3.6|6.3|96.0|350|-292|-369|
Delta Cephei|δ Cep|22 29 10.27|+58 24 54.7|4.1|6.3|40.6|191|0|0|Cefeida prototipo
Zeta Aquarii|ζ Aqr|22 28 49.80|-00 01 12.0|4.3|4.5|2.3|165|0|0|
Alfa Centauri|α Cen|14 39 36.494|-60 50 02.37|0.0|1.3|8.0||-3679|474|Separación variable (79 años)
Polaris|α UMi|02 31 49.095|+89 15 50.79|2.0|9.0|18.2|231|44|-12|
Nu Draconis|ν Dra|17 32 10.57|+55 11 03.3|4.9|4.9|63.0|311|0|0|Ojos del dragón
Zeta Lyrae|ζ Lyr|18 44 46.35|+37 36 18.2|4.3|5.6|43.7|150|0|0|
24 Comae|24 Com|12 35 07.76|+18 22 37.4|5.0|6.6|20.3|270|0|0|Naranja y azul
Beta Cephei|β Cep|21 28 39.60|+70 33 38.6|3.2|7.9|13.3|249|0|0|
Mintaka|δ Ori|05 32 00.400|-00 17 56.74|2.2|6.9|52.0|0|1|-1|
32 Eridani|32 Eri|03 54 17.50|-02 57 17.0|4.8|5.9|6.9|348|0|0|
Zubenelgenubi|α Lib|14 50 52.713|-16 02 30.40|2.8|5.2|231.0|314|-105|-68|Doble amplia
"""

    private fun ra(text: String): Double = text.trim().split(" ").map { it.toDouble() }
        .let { (h, m, s) -> h + m / 60 + s / 3600 }

    private fun dec(text: String): Double {
        val t = text.trim()
        val neg = t.startsWith("-")
        val (d, m, s) = t.trimStart('+', '-').split(" ").map { it.toDouble() }
        val v = d + m / 60 + s / 3600
        return if (neg) -v else v
    }

    val namedStars: List<NamedStar> by lazy {
        NAMED.lines().filter { it.isNotBlank() }.map { line ->
            val f = line.split("|")
            NamedStar(
                name = f[0], bayer = f[1], hip = f[2].toInt(),
                j2000 = EquatorialCoord.fromHoursDegrees(ra(f[3]), dec(f[4])),
                vMag = f[5].toDouble(), pmRaMasYr = f[6].toDouble(), pmDecMasYr = f[7].toDouble(),
            )
        }.sortedBy { it.vMag }
    }

    val doubleStars: List<DoubleStar> by lazy {
        DOUBLES.lines().filter { it.isNotBlank() }.map { line ->
            val f = line.split("|")
            DoubleStar(
                name = f[0], designation = f[1],
                j2000 = EquatorialCoord.fromHoursDegrees(ra(f[2]), dec(f[3])),
                magA = f[4].toDouble(), magB = f[5].toDouble(), separationArcsec = f[6].toDouble(),
                positionAngleDeg = f[7].toDoubleOrNull(),
                pmRaMasYr = f[8].toDouble(), pmDecMasYr = f[9].toDouble(), notes = f.getOrElse(10) { "" },
            )
        }
    }
}
