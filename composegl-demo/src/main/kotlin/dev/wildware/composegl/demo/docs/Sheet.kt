package dev.wildware.composegl.demo.docs

/**
 * The reference art sheet, measured.
 *
 * Generated from the sheet's own pixels rather than typed by eye: where each piece sits on the
 * 1600 by 1200 page, how big it is, how round its corners are, how thick its line is, and the
 * colours read down the middle of it. [ArtSheet] draws this list and nothing else.
 *
 * A piece taller than it is wide has its colours read across it instead of down it, which is how
 * the upright bars and the sliders' posts are shaded. A [Piece.fill] is the piece inside a piece: a
 * bar's fill, a switch's knob, the face of a checkbox.
 */
internal object Sheet {

    /** The bright band across the top of a piece, measured where the sheet draws it. */
    class Gloss(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val stops: List<Pair<Float, Long>>,
    )

    /** What a piece is, for the few that are not a rounded box. */
    enum class Kind { Box, Plate, Banner, Gem, Berry }

    /** One piece of the page. */
    class Piece(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val corner: Float,
        val ink: Long,
        val outline: Float,
        val kind: Kind,
        val across: Boolean,
        val stops: List<Pair<Float, Long>>,
        val gloss: Gloss? = null,
        val fill: Piece? = null,
    )

    val pieces: List<Piece> = listOf(
        Piece(
            40f, 17f, 298f, 108f, 29.0f, 0x050400L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x724627L, 0.077f to 0xF09E54L, 0.154f to 0x9F4D1DL, 0.231f to 0xA24F1FL, 0.308f to 0x9F4D1FL, 0.385f to 0x9C4D1EL, 0.462f to 0x9E4C20L, 0.538f to 0x9E4C1EL, 0.615f to 0x9E4C20L, 0.692f to 0x9D4E21L, 0.769f to 0x954B1CL, 0.846f to 0xA04A22L, 0.923f to 0x9B4C22L, 1.000f to 0x552511L),
            gloss = Gloss(63f, 24f, 252f, 8f, listOf(0.000f to 0xE8A168L, 0.333f to 0xEC9946L, 0.667f to 0xFFAF5BL, 1.000f to 0xC4712FL)),
        ),
        Piece(
            350f, 17f, 298f, 108f, 32.0f, 0x0A0100L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x7B6F39L, 0.077f to 0xFFD665L, 0.154f to 0xEA9232L, 0.231f to 0xE3882CL, 0.308f to 0xE98E32L, 0.385f to 0xEA8F33L, 0.462f to 0xE5882CL, 0.538f to 0xEA8F33L, 0.615f to 0xE88E33L, 0.692f to 0xE78B31L, 0.769f to 0xDE842CL, 0.846f to 0xE4892FL, 0.923f to 0xA94E00L, 1.000f to 0x50230DL),
            gloss = Gloss(374f, 24f, 251f, 7f, listOf(0.000f to 0xF0E07FL, 0.333f to 0xFBDB5FL, 0.667f to 0xF4D95AL, 1.000f to 0xFFD665L)),
        ),
        Piece(
            661f, 16f, 298f, 108f, 28.0f, 0x000000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x633A1AL, 0.077f to 0xB6774FL, 0.154f to 0x5C2C1AL, 0.231f to 0x5B2D18L, 0.308f to 0x572E19L, 0.385f to 0x5A2E1AL, 0.462f to 0x5A2E1AL, 0.538f to 0x5A2E1AL, 0.615f to 0x5A2E1AL, 0.692f to 0x5A2E1AL, 0.769f to 0x572E19L, 0.846f to 0x562D15L, 0.923f to 0x7D442CL, 1.000f to 0x341816L),
            gloss = Gloss(684f, 23f, 252f, 8f, listOf(0.000f to 0xCF956BL, 0.333f to 0xB66D46L, 0.667f to 0xBC774BL, 1.000f to 0x814B31L)),
        ),
        Piece(
            39f, 137f, 253f, 90f, 45.0f, 0x000000L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x7FC955L, 0.077f to 0x9AF656L, 0.154f to 0x4DC123L, 0.231f to 0x47B927L, 0.308f to 0x42B526L, 0.385f to 0x3DAF22L, 0.462f to 0x3AAB20L, 0.538f to 0x36AA1EL, 0.615f to 0x31A71DL, 0.692f to 0x2FA51CL, 0.769f to 0x2EA41BL, 0.846f to 0x2BA21EL, 0.923f to 0x0A7504L, 1.000f to 0x1C4D13L),
            gloss = Gloss(62f, 143f, 208f, 12f, listOf(0.000f to 0x7FC955L, 0.333f to 0x8BF14FL, 0.667f to 0x7EE745L, 1.000f to 0x4FC423L)),
        ),
        Piece(
            297f, 136f, 248f, 91f, 45.5f, 0x000400L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x334E00L, 0.077f to 0xDAFD5DL, 0.154f to 0x92EB32L, 0.231f to 0x8EE82DL, 0.308f to 0x8AE327L, 0.385f to 0x86E026L, 0.462f to 0x84DD25L, 0.538f to 0x82DB25L, 0.615f to 0x80D823L, 0.692f to 0x7ED824L, 0.769f to 0x79D720L, 0.846f to 0x76D826L, 0.923f to 0x3B9906L, 1.000f to 0x2A5A0EL),
            gloss = Gloss(323f, 143f, 197f, 8f, listOf(0.000f to 0xB9DD6DL, 0.333f to 0xCEF65AL, 0.667f to 0xDAFD5DL, 1.000f to 0xBBF94AL)),
        ),
        Piece(
            549f, 136f, 245f, 90f, 45.0f, 0x000400L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x06510AL, 0.077f to 0x48D14DL, 0.154f to 0x198F29L, 0.231f to 0x158728L, 0.308f to 0x148627L, 0.385f to 0x128124L, 0.462f to 0x0F8125L, 0.538f to 0x0E8023L, 0.615f to 0x0D7E22L, 0.692f to 0x0F7E24L, 0.769f to 0x0D7E26L, 0.846f to 0x087D21L, 0.923f to 0x055E19L, 1.000f to 0x0E4920L),
            gloss = Gloss(574f, 143f, 196f, 8f, listOf(0.000f to 0x57C55EL, 0.333f to 0x38C640L, 0.667f to 0x48D14DL, 1.000f to 0x2CAB3BL)),
        ),
        Piece(
            807f, 137f, 247f, 90f, 45.0f, 0x000004L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x003751L, 0.077f to 0x3ED5FDL, 0.154f to 0x009AFCL, 0.231f to 0x0094FAL, 0.308f to 0x008FF9L, 0.385f to 0x008BFAL, 0.462f to 0x0087F9L, 0.538f to 0x0084F9L, 0.615f to 0x007FF9L, 0.692f to 0x007EF8L, 0.769f to 0x007CF7L, 0.846f to 0x0080F8L, 0.923f to 0x0054B8L, 1.000f to 0x043067L),
            gloss = Gloss(833f, 143f, 196f, 8f, listOf(0.000f to 0x46A5C8L, 0.333f to 0x29C1EEL, 0.667f to 0x3ED5FDL, 1.000f to 0x29C4FFL)),
        ),
        Piece(
            1058f, 136f, 255f, 91f, 45.5f, 0x010103L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x004E56L, 0.077f to 0x5AF0FFL, 0.154f to 0x04BDFDL, 0.231f to 0x01B8FAL, 0.308f to 0x00B5F8L, 0.385f to 0x00B0FAL, 0.462f to 0x01B0FAL, 0.538f to 0x00ADF9L, 0.615f to 0x00ACF8L, 0.692f to 0x00AAF9L, 0.769f to 0x00AAF9L, 0.846f to 0x03A8FBL, 0.923f to 0x0079C9L, 1.000f to 0x0C4D78L),
            gloss = Gloss(1084f, 143f, 204f, 8f, listOf(0.000f to 0x5CD6E4L, 0.333f to 0x43E4F4L, 0.667f to 0x5AF0FFL, 1.000f to 0x30D7FFL)),
        ),
        Piece(
            1318f, 137f, 245f, 90f, 45.0f, 0x020002L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x214F84L, 0.077f to 0x2C93EEL, 0.154f to 0x0064BCL, 0.231f to 0x0063BCL, 0.308f to 0x0060BAL, 0.385f to 0x005EB6L, 0.462f to 0x005EB5L, 0.538f to 0x005BB2L, 0.615f to 0x005BB2L, 0.692f to 0x005AB4L, 0.769f to 0x0059AFL, 0.846f to 0x0057AFL, 0.923f to 0x00459DL, 1.000f to 0x0D3260L),
            gloss = Gloss(1343f, 144f, 196f, 7f, listOf(0.000f to 0x368CD9L, 0.333f to 0x1882DBL, 0.667f to 0x268DE8L, 1.000f to 0x1F87E2L)),
        ),
        Piece(
            40f, 236f, 253f, 95f, 47.5f, 0x000100L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x877F27L, 0.077f to 0xFCFB5BL, 0.154f to 0xFECA20L, 0.231f to 0xFCC123L, 0.308f to 0xFCBD23L, 0.385f to 0xFBBA22L, 0.462f to 0xFCB620L, 0.538f to 0xFBB51FL, 0.615f to 0xFBB41EL, 0.692f to 0xFDB21CL, 0.769f to 0xFDB01AL, 0.846f to 0xFCAF19L, 0.923f to 0xD07003L, 1.000f to 0x7D400DL),
            gloss = Gloss(67f, 243f, 201f, 8f, listOf(0.000f to 0xF9F16DL, 0.333f to 0xF9F249L, 0.667f to 0xFCFB5BL, 1.000f to 0xF4E13DL)),
        ),
        Piece(
            297f, 236f, 249f, 95f, 47.5f, 0x000100L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x7C7938L, 0.077f to 0xFDFB7EL, 0.154f to 0xFBE342L, 0.231f to 0xFDDE40L, 0.308f to 0xFBDB3DL, 0.385f to 0xFCD73DL, 0.462f to 0xFCD23BL, 0.538f to 0xFBD13AL, 0.615f to 0xFCCF3BL, 0.692f to 0xFCCF3BL, 0.769f to 0xFCCD39L, 0.846f to 0xFCCE38L, 0.923f to 0xCC8E1AL, 1.000f to 0x60370BL),
        ),
        Piece(
            548f, 236f, 246f, 93f, 46.5f, 0x020100L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0xA0770EL, 0.077f to 0xFCC50BL, 0.154f to 0xF18B00L, 0.231f to 0xEB8600L, 0.308f to 0xE98300L, 0.385f to 0xE87F00L, 0.462f to 0xE77E01L, 0.538f to 0xE57C00L, 0.615f to 0xE47A00L, 0.692f to 0xE47700L, 0.769f to 0xE67600L, 0.846f to 0xE67500L, 0.923f to 0xB24600L, 1.000f to 0x914C25L),
            gloss = Gloss(574f, 243f, 195f, 7f, listOf(0.000f to 0xFBC532L, 0.333f to 0xF8B907L, 0.667f to 0xFCB905L, 1.000f to 0xFCBE0AL)),
        ),
        Piece(
            806f, 236f, 248f, 94f, 47.0f, 0x010200L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x7D3438L, 0.077f to 0xFA7F7FL, 0.154f to 0xFC3A41L, 0.231f to 0xF9323AL, 0.308f to 0xF9323AL, 0.385f to 0xFA3039L, 0.462f to 0xFA3038L, 0.538f to 0xFA2E37L, 0.615f to 0xF92D36L, 0.692f to 0xF82C35L, 0.769f to 0xF82C36L, 0.846f to 0xF82933L, 0.923f to 0xAE0817L, 1.000f to 0x730B10L),
            gloss = Gloss(832f, 243f, 197f, 8f, listOf(0.000f to 0xDB7375L, 0.333f to 0xEE7371L, 0.667f to 0xFA7F7FL, 1.000f to 0xF45B61L)),
        ),
        Piece(
            1058f, 236f, 254f, 93f, 46.5f, 0x000201L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x874F55L, 0.077f to 0xFFAAAAL, 0.154f to 0xFA6C6EL, 0.231f to 0xF96466L, 0.308f to 0xF86363L, 0.385f to 0xF95F62L, 0.462f to 0xFC5E5FL, 0.538f to 0xFB5D5BL, 0.615f to 0xFA5A5BL, 0.692f to 0xF9585AL, 0.769f to 0xF9585AL, 0.846f to 0xF6595AL, 0.923f to 0xD9232BL, 1.000f to 0x992D32L),
            gloss = Gloss(1084f, 243f, 203f, 8f, listOf(0.000f to 0xEFA1A9L, 0.333f to 0xF99DA3L, 0.667f to 0xFFAAAAL, 1.000f to 0xFE818BL)),
        ),
        Piece(
            1316f, 236f, 247f, 93f, 46.5f, 0x010200L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x861B21L, 0.077f to 0xFB393BL, 0.154f to 0xC71523L, 0.231f to 0xC2131EL, 0.308f to 0xBF121DL, 0.385f to 0xBD121EL, 0.462f to 0xBD121FL, 0.538f to 0xB80F1BL, 0.615f to 0xB80F1DL, 0.692f to 0xB70D1BL, 0.769f to 0xB50C1AL, 0.846f to 0xB50C1AL, 0.923f to 0x85000AL, 1.000f to 0x4F0812L),
            gloss = Gloss(1341f, 243f, 196f, 8f, listOf(0.000f to 0xD84347L, 0.333f to 0xF53838L, 0.667f to 0xFB393BL, 1.000f to 0xE02428L)),
        ),
        Piece(
            39f, 343f, 252f, 92f, 46.0f, 0x000000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x343636L, 0.077f to 0xCFCFCFL, 0.154f to 0x8D8D8DL, 0.231f to 0x888888L, 0.308f to 0x868686L, 0.385f to 0x868686L, 0.462f to 0x858585L, 0.538f to 0x828282L, 0.615f to 0x818181L, 0.692f to 0x808080L, 0.769f to 0x7D7D7DL, 0.846f to 0x7F7F7FL, 0.923f to 0x5B5B5BL, 1.000f to 0x4D4D4DL),
            gloss = Gloss(63f, 349f, 207f, 9f, listOf(0.000f to 0xAAACABL, 0.333f to 0xCBCBCBL, 0.667f to 0xCFCFCFL, 1.000f to 0xA4A4A4L)),
        ),
        Piece(
            297f, 342f, 247f, 91f, 45.5f, 0x000000L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x73716FL, 0.077f to 0xFDFDFDL, 0.154f to 0xD9D9D9L, 0.231f to 0xD5D5D5L, 0.308f to 0xD2D2D2L, 0.385f to 0xD1D1D1L, 0.462f to 0xCECECEL, 0.538f to 0xCCCCCCL, 0.615f to 0xCACACAL, 0.692f to 0xCACACAL, 0.769f to 0xC6C6C6L, 0.846f to 0xC6C6C6L, 0.923f to 0x939393L, 1.000f to 0x5E5E5EL),
            gloss = Gloss(323f, 349f, 198f, 8f, listOf(0.000f to 0xFFFFFDL, 0.333f to 0xF1EFEEL, 0.667f to 0xFDFDFDL, 1.000f to 0xECECECL)),
        ),
        Piece(
            549f, 341f, 246f, 92f, 46.0f, 0x000000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x2F3231L, 0.077f to 0x9D9D9DL, 0.154f to 0x636363L, 0.231f to 0x616161L, 0.308f to 0x5D5D5DL, 0.385f to 0x5C5C5CL, 0.462f to 0x5B5B5BL, 0.538f to 0x5A5A5AL, 0.615f to 0x5A5A5AL, 0.692f to 0x5A5A5AL, 0.769f to 0x575757L, 0.846f to 0x565656L, 0.923f to 0x404040L, 1.000f to 0x393939L),
            gloss = Gloss(575f, 347f, 196f, 8f, listOf(0.000f to 0x8E908FL, 0.333f to 0x9FA2A1L, 0.667f to 0x9D9D9DL, 1.000f to 0x9B9B9BL)),
        ),
        Piece(
            815f, 339f, 134f, 128f, 23.0f, 0x050000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x562304L, 0.077f to 0x9A4A1AL, 0.154f to 0x92481EL, 0.231f to 0x934921L, 0.308f to 0x934921L, 0.385f to 0x964B24L, 0.462f to 0x91461FL, 0.538f to 0x944A23L, 0.615f to 0x924720L, 0.692f to 0x934921L, 0.769f to 0x91451EL, 0.846f to 0x92461FL, 0.923f to 0x8D4218L, 1.000f to 0x553017L),
            gloss = Gloss(835f, 345f, 95f, 8f, listOf(0.000f to 0xCB8556L, 0.333f to 0xE88746L, 0.667f to 0xEE8B49L, 1.000f to 0xCC763EL)),
        ),
        Piece(
            964f, 338f, 135f, 128f, 25.0f, 0x020500L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0xCAC26AL, 0.077f to 0xF7BC2AL, 0.154f to 0xFDB529L, 0.231f to 0xFCB428L, 0.308f to 0xFDB32AL, 0.385f to 0xFDB32AL, 0.462f to 0xFAAF27L, 0.538f to 0xFBAF27L, 0.615f to 0xF7AA22L, 0.692f to 0xFBAA23L, 0.769f to 0xFCA922L, 0.846f to 0xFAA51CL, 0.923f to 0xFFA42DL, 1.000f to 0x924D0DL),
            gloss = Gloss(983f, 345f, 97f, 7f, listOf(0.000f to 0xF6EE78L, 0.333f to 0xF3EB65L, 0.667f to 0xFCF763L, 1.000f to 0xFFF458L)),
        ),
        Piece(
            1113f, 338f, 135f, 128f, 24.0f, 0x000300L, 5f, Kind.Box, across = false,
            listOf(0.000f to 0x45ADBEL, 0.077f to 0x0091F2L, 0.154f to 0x008BFAL, 0.231f to 0x008AFBL, 0.308f to 0x0086F8L, 0.385f to 0x0081F7L, 0.462f to 0x007CF7L, 0.538f to 0x007BF7L, 0.615f to 0x0079F4L, 0.692f to 0x0075F0L, 0.769f to 0x0071EEL, 0.846f to 0x0070EAL, 0.923f to 0x0B72EDL, 1.000f to 0x001D53L),
            gloss = Gloss(1132f, 344f, 98f, 9f, listOf(0.000f to 0x45ADBEL, 0.333f to 0x2DD8F0L, 0.667f to 0x34DCFEL, 1.000f to 0x0AA2F1L)),
        ),
        Piece(
            1264f, 338f, 138f, 128f, 23.0f, 0x020003L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0xB75862L, 0.077f to 0xEE2E3BL, 0.154f to 0xF13139L, 0.231f to 0xEE2D37L, 0.308f to 0xEB2A35L, 0.385f to 0xE92934L, 0.462f to 0xE62A33L, 0.538f to 0xE52532L, 0.615f to 0xE22531L, 0.692f to 0xDD2230L, 0.769f to 0xDB1F2DL, 0.846f to 0xDA1E2CL, 0.923f to 0xDF2539L, 1.000f to 0x59050FL),
            gloss = Gloss(1283f, 345f, 100f, 7f, listOf(0.000f to 0xF17886L, 0.333f to 0xF07177L, 0.667f to 0xF97073L, 1.000f to 0xFF6B6FL)),
        ),
        Piece(
            1418f, 338f, 134f, 127f, 23.0f, 0x000000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x6D6B69L, 0.077f to 0x828282L, 0.154f to 0x7E7E7EL, 0.231f to 0x7E7E7EL, 0.308f to 0x7B7B7BL, 0.385f to 0x7A7A7AL, 0.462f to 0x787878L, 0.538f to 0x757575L, 0.615f to 0x767676L, 0.692f to 0x737373L, 0.769f to 0x717171L, 0.846f to 0x707070L, 0.923f to 0x787878L, 1.000f to 0x363636L),
            gloss = Gloss(1436f, 345f, 97f, 8f, listOf(0.000f to 0xC6C6C4L, 0.333f to 0xB9BAB7L, 0.667f to 0xC1C2BFL, 1.000f to 0x9C9C9CL)),
        ),
        Piece(
            40f, 448f, 125f, 199f, 25.0f, 0x030100L, 4f, Kind.Box, across = true,
            listOf(0.000f to 0x4D1C01L, 0.077f to 0xAA5522L, 0.154f to 0x974920L, 0.231f to 0x96481FL, 0.308f to 0x984A22L, 0.385f to 0x984A22L, 0.462f to 0x984C23L, 0.538f to 0x984C23L, 0.615f to 0x9A4B23L, 0.692f to 0x95471AL, 0.769f to 0x96481DL, 0.846f to 0x97481DL, 0.923f to 0x9A4A20L, 1.000f to 0x632F19L),
        ),
        Piece(
            176f, 448f, 127f, 200f, 25.0f, 0x000000L, 4f, Kind.Box, across = true,
            listOf(0.000f to 0x643107L, 0.077f to 0xDE842EL, 0.154f to 0xDD7C2EL, 0.231f to 0xDB7C30L, 0.308f to 0xDB7C30L, 0.385f to 0xDD7D31L, 0.462f to 0xDD7D31L, 0.538f to 0xDA7D31L, 0.615f to 0xD97C2FL, 0.692f to 0xDA7B2FL, 0.769f to 0xDA7A30L, 0.846f to 0xD8782EL, 0.923f to 0xD5792AL, 1.000f to 0x814428L),
        ),
        Piece(
            315f, 448f, 128f, 199f, 25.0f, 0x050000L, 5f, Kind.Box, across = true,
            listOf(0.000f to 0x724336L, 0.077f to 0x5B2714L, 0.154f to 0x552B18L, 0.231f to 0x552B18L, 0.308f to 0x562C17L, 0.385f to 0x562C17L, 0.462f to 0x562C17L, 0.538f to 0x562C17L, 0.615f to 0x552B16L, 0.692f to 0x552B16L, 0.769f to 0x552B16L, 0.846f to 0x542917L, 0.923f to 0x562918L, 1.000f to 0x4C2C1BL),
        ),
        Piece(
            531f, 466f, 146f, 23f, 7.0f, 0x080000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x80613AL, 0.077f to 0xE4AA66L, 0.154f to 0xF7AB5AL, 0.231f to 0xFBA95AL, 0.308f to 0xEC9347L, 0.385f to 0xCE6C23L, 0.462f to 0xD6742CL, 0.538f to 0xD27329L, 0.615f to 0xD2742CL, 0.692f to 0xC86B23L, 0.769f to 0xC66720L, 0.846f to 0xC66720L, 0.923f to 0xCB6B26L, 1.000f to 0xCC6D25L),
        ),
        Piece(
            453f, 482f, 301f, 82f, 25.0f, 0x000000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0xCE7129L, 0.077f to 0xCC6D25L, 0.154f to 0xCD6A26L, 0.231f to 0xCC6C27L, 0.308f to 0xAD5722L, 0.385f to 0x2F0600L, 0.462f to 0x703418L, 0.538f to 0x7D3B1AL, 0.615f to 0x7D3D1BL, 0.692f to 0x7C3E1BL, 0.769f to 0x7A3F1BL, 0.846f to 0x833C1FL, 0.923f to 0xA2582FL, 1.000f to 0x4A2709L),
        ),
        Piece(
            855f, 471f, 141f, 19f, 9.5f, 0x080202L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x857F57L, 0.077f to 0xFFF7C5L, 0.154f to 0xFEF2BFL, 0.231f to 0xFDEDB4L, 0.308f to 0xFFEAAAL, 0.385f to 0xEDC988L, 0.462f to 0xEBB874L, 0.538f to 0xF1B973L, 0.615f to 0xE8B86FL, 0.692f to 0xE7B971L, 0.769f to 0xEAB879L, 0.846f to 0xEBB87CL, 0.923f to 0xEBB97AL, 1.000f to 0xEBB97AL),
        ),
        Piece(
            765f, 483f, 321f, 81f, 21.0f, 0x050000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0xF1B973L, 0.077f to 0xEBB97AL, 0.154f to 0xE8B776L, 0.231f to 0xCC9C60L, 0.308f to 0xF2C78BL, 0.385f to 0xEEC589L, 0.462f to 0xECC689L, 0.538f to 0xECC58AL, 0.615f to 0xEBC68AL, 0.692f to 0xEBC489L, 0.769f to 0xEDC389L, 0.846f to 0xEBC288L, 0.923f to 0xFACE95L, 1.000f to 0x8A6340L),
        ),
        Piece(
            1203f, 472f, 134f, 19f, 9.5f, 0x090000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x663125L, 0.077f to 0xCB7D58L, 0.154f to 0xCB7140L, 0.231f to 0xD87B49L, 0.308f to 0xCD7546L, 0.385f to 0x9E5027L, 0.462f to 0x7E3B1AL, 0.538f to 0x793E21L, 0.615f to 0x743E24L, 0.692f to 0x753C21L, 0.769f to 0x733B20L, 0.846f to 0x733B20L, 0.923f to 0x723A1FL, 1.000f to 0x713A21L),
        ),
        Piece(
            1097f, 484f, 345f, 80f, 23.0f, 0x080000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x793E21L, 0.077f to 0x713A21L, 0.154f to 0x6E361DL, 0.231f to 0x683219L, 0.308f to 0x472213L, 0.385f to 0x421C0FL, 0.462f to 0x532919L, 0.538f to 0x532917L, 0.615f to 0x542819L, 0.692f to 0x532718L, 0.769f to 0x532718L, 0.846f to 0x502916L, 0.923f to 0x753F28L, 1.000f to 0x442413L),
        ),
        Piece(
            508f, 573f, 295f, 80f, 40.0f, 0x030000L, 4f, Kind.Banner, across = false,
            listOf(0.000f to 0x663A1BL, 0.077f to 0xE38A44L, 0.154f to 0xA65122L, 0.231f to 0x9E4D1FL, 0.308f to 0x9D4C20L, 0.385f to 0x9A4A20L, 0.462f to 0x98491EL, 0.538f to 0x98491EL, 0.615f to 0x9A4B21L, 0.692f to 0x96471FL, 0.769f to 0x96471CL, 0.846f to 0x9A4A1EL, 0.923f to 0x6D300BL, 1.000f to 0x3A170BL),
        ),
        Piece(
            823f, 573f, 300f, 81f, 40.5f, 0x010000L, 4f, Kind.Banner, across = false,
            listOf(0.000f to 0x4E4B32L, 0.077f to 0xFFEFB4L, 0.154f to 0xEDC888L, 0.231f to 0xECC687L, 0.308f to 0xEAC284L, 0.385f to 0xEAC284L, 0.462f to 0xEAC382L, 0.538f to 0xEAC382L, 0.615f to 0xE6BC7FL, 0.692f to 0xE8BD80L, 0.769f to 0xE9C182L, 0.846f to 0xE4BD81L, 0.923f to 0xB38853L, 1.000f to 0x6F4A2CL),
        ),
        Piece(
            1133f, 573f, 304f, 81f, 40.5f, 0x060001L, 4f, Kind.Banner, across = false,
            listOf(0.000f to 0x562E17L, 0.077f to 0xA4643EL, 0.154f to 0x572F18L, 0.231f to 0x562C17L, 0.308f to 0x552B16L, 0.385f to 0x572B17L, 0.462f to 0x572B17L, 0.538f to 0x542B16L, 0.615f to 0x552B16L, 0.692f to 0x542C18L, 0.769f to 0x542B18L, 0.846f to 0x542915L, 0.923f to 0x623421L, 1.000f to 0x351A13L),
        ),
        Piece(
            40f, 678f, 495f, 39f, 11.0f, 0x0B0000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0xA16E4EL, 0.077f to 0xB76E3CL, 0.154f to 0x320C00L, 0.231f to 0x381606L, 0.308f to 0x391707L, 0.385f to 0x3B1607L, 0.462f to 0x3C1809L, 0.538f to 0x3C1809L, 0.615f to 0x3C1809L, 0.692f to 0x391709L, 0.769f to 0x391709L, 0.846f to 0x3E1505L, 0.923f to 0x9D6141L, 1.000f to 0x7C4120L),
            gloss = Gloss(53f, 681f, 469f, 4f, listOf(0.000f to 0xA16E4EL, 0.333f to 0xD68E5FL, 0.667f to 0xB76E3CL, 1.000f to 0x793A14L)),
        ),
        Piece(
            566f, 676f, 183f, 41f, 20.5f, 0x090000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xBB8756L, 0.077f to 0xB7723BL, 0.154f to 0x300B00L, 0.231f to 0x3B1506L, 0.308f to 0x3B1607L, 0.385f to 0x3C1809L, 0.462f to 0x3C1809L, 0.538f to 0x3C1809L, 0.615f to 0x3C1807L, 0.692f to 0x3C1807L, 0.769f to 0x3C1605L, 0.846f to 0x3B1607L, 0.923f to 0xA76438L, 1.000f to 0x76411DL),
            gloss = Gloss(579f, 680f, 156f, 4f, listOf(0.000f to 0xBB8756L, 0.333f to 0xCF8D51L, 0.667f to 0xB7723BL, 1.000f to 0x703406L)),
        ),
        Piece(
            784f, 676f, 39f, 234f, 12.0f, 0x060200L, 3f, Kind.Box, across = true,
            listOf(0.000f to 0x965F40L, 0.077f to 0xA35F32L, 0.154f to 0x3E1203L, 0.231f to 0x441809L, 0.308f to 0x451B0BL, 0.385f to 0x461D0AL, 0.462f to 0x471E0CL, 0.538f to 0x451F0CL, 0.615f to 0x451F0CL, 0.692f to 0x441E08L, 0.769f to 0x441D0CL, 0.846f to 0x441B02L, 0.923f to 0xA85B3AL, 1.000f to 0x65351BL),
        ),
        Piece(
            849f, 676f, 34f, 234f, 12.0f, 0x0D0000L, 2f, Kind.Box, across = true,
            listOf(0.000f to 0x8E6233L, 0.077f to 0x7F5812L, 0.154f to 0x89732FL, 0.231f to 0xFCD147L, 0.308f to 0xF6AF20L, 0.385f to 0xF9AF21L, 0.462f to 0xFDB21CL, 0.538f to 0xFEB120L, 0.615f to 0xFCAD23L, 0.692f to 0xFCAB20L, 0.769f to 0xEDA426L, 0.846f to 0x692D01L, 0.923f to 0x662C0DL, 1.000f to 0x552D1EL),
        ),
        Piece(
            905f, 676f, 33f, 234f, 10.0f, 0x060000L, 2f, Kind.Box, across = true,
            listOf(0.000f to 0x512813L, 0.077f to 0xA5755FL, 0.154f to 0x001E3EL, 0.231f to 0x2BA5F3L, 0.308f to 0x007FEDL, 0.385f to 0x007BF5L, 0.462f to 0x027AF3L, 0.538f to 0x0379F3L, 0.615f to 0x037AF9L, 0.692f to 0x0279F7L, 0.769f to 0x0772E5L, 0.846f to 0x083C83L, 0.923f to 0x271512L, 1.000f to 0x764129L),
        ),
        Piece(
            961f, 676f, 33f, 234f, 10.0f, 0x010100L, 2f, Kind.Box, across = true,
            listOf(0.000f to 0x4B2008L, 0.077f to 0x775539L, 0.154f to 0x1F4B00L, 0.231f to 0x74DE49L, 0.308f to 0x3BBC18L, 0.385f to 0x33C212L, 0.462f to 0x36C014L, 0.538f to 0x39BE16L, 0.615f to 0x37BD14L, 0.692f to 0x33B810L, 0.769f to 0x22960EL, 0.846f to 0x012000L, 0.923f to 0x784830L, 1.000f to 0x351D0EL),
        ),
        Piece(
            1017f, 676f, 34f, 234f, 12.0f, 0x100000L, 3f, Kind.Box, across = true,
            listOf(0.000f to 0x774532L, 0.077f to 0x68231BL, 0.154f to 0x862E2CL, 0.231f to 0xEC4950L, 0.308f to 0xF53339L, 0.385f to 0xF82F3CL, 0.462f to 0xF9303FL, 0.538f to 0xF9303FL, 0.615f to 0xFB3141L, 0.692f to 0xFB313EL, 0.769f to 0xE12836L, 0.846f to 0x690A0EL, 0.923f to 0x501104L, 1.000f to 0x58271EL),
        ),
        Piece(
            1077f, 676f, 37f, 235f, 12.0f, 0x040000L, 2f, Kind.Box, across = true,
            listOf(0.000f to 0x7C482EL, 0.077f to 0x9B512DL, 0.154f to 0x3C0C00L, 0.231f to 0x3D1D0AL, 0.308f to 0x3E190AL, 0.385f to 0x3F1A0BL, 0.462f to 0x3F1A0BL, 0.538f to 0x3F1A0BL, 0.615f to 0x3F1A0BL, 0.692f to 0x3F1A0BL, 0.769f to 0x421909L, 0.846f to 0x591E04L, 0.923f to 0xB26645L, 1.000f to 0x5D3112L),
        ),
        Piece(
            1139f, 675f, 82f, 95f, 47.5f, 0x010000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x7D4721L, 0.077f to 0xE28B48L, 0.154f to 0x95451DL, 0.231f to 0x8F461EL, 0.308f to 0x92461FL, 0.385f to 0x95481FL, 0.462f to 0x94471EL, 0.538f to 0x91441EL, 0.615f to 0x90451EL, 0.692f to 0x90451EL, 0.769f to 0x66290EL, 0.846f to 0x1D0406L, 0.923f to 0x331206L, 1.000f to 0x55382EL),
            gloss = Gloss(1164f, 681f, 32f, 7f, listOf(0.000f to 0xDB945DL, 0.333f to 0xE28B44L, 0.667f to 0xE48941L, 1.000f to 0xBC692BL)),
        ),
        Piece(
            1233f, 675f, 81f, 96f, 48.0f, 0x010101L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x575A03L, 0.077f to 0xFCEB48L, 0.154f to 0xFFD037L, 0.231f to 0xFDC62AL, 0.308f to 0xFEC228L, 0.385f to 0xFCB824L, 0.462f to 0xFCB321L, 0.538f to 0xFBAD1CL, 0.615f to 0xFCAA1CL, 0.692f to 0xF8A51AL, 0.769f to 0xC96703L, 0.846f to 0x0B0000L, 0.923f to 0x481A12L, 1.000f to 0x6D3E22L),
            gloss = Gloss(1257f, 681f, 33f, 9f, listOf(0.000f to 0xE4E763L, 0.333f to 0xF8EF47L, 0.667f to 0xFBED49L, 1.000f to 0xF6D939L)),
        ),
        Piece(
            1329f, 675f, 69f, 96f, 48.0f, 0x000102L, 2f, Kind.Gem, across = false,
            listOf(0.000f to 0x0F3031L, 0.077f to 0xC9FFFFL, 0.154f to 0x8AF7FCL, 0.231f to 0x13CAF7L, 0.308f to 0x02AAFFL, 0.385f to 0x0197FCL, 0.462f to 0x0093FAL, 0.538f to 0x008FF8L, 0.615f to 0x019AFBL, 0.692f to 0x028DEFL, 0.769f to 0x0085F3L, 0.846f to 0x2D4B77L, 0.923f to 0x02244CL, 1.000f to 0x5D3114L),
        ),
        Piece(
            1413f, 675f, 69f, 96f, 48.0f, 0x000100L, 3f, Kind.Berry, across = false,
            listOf(0.000f to 0x54B96FL, 0.077f to 0x0AA92BL, 0.154f to 0x004F0EL, 0.231f to 0x474E22L, 0.308f to 0xE03639L, 0.385f to 0xFF8B85L, 0.462f to 0xFD0B26L, 0.538f to 0xE53B3BL, 0.615f to 0xB71D1AL, 0.692f to 0xBE0E21L, 0.769f to 0x9B0C23L, 0.846f to 0x250003L, 0.923f to 0x3B1108L, 1.000f to 0x3A1C0FL),
        ),
        Piece(
            1499f, 691f, 85f, 84f, 17.0f, 0x0C0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x331E0AL, 0.077f to 0xFFF6E5L, 0.154f to 0xFAE8BEL, 0.231f to 0xF8E0BAL, 0.308f to 0xF5DEB7L, 0.385f to 0xF5DEB7L, 0.462f to 0xFADFB2L, 0.538f to 0x2D1400L, 0.615f to 0x040000L, 0.692f to 0x32130BL, 0.769f to 0x3B120BL, 0.846f to 0x3C190EL, 0.923f to 0x441903L, 1.000f to 0x48241BL),
            fill = Piece(
                1507f, 699f, 69f, 40f, 20.0f, 0xD5AA6EL, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xD5AA6EL, 0.077f to 0xFFFCF1L, 0.154f to 0xFAE9C5L, 0.231f to 0xF9E4B8L, 0.308f to 0xF9E1BBL, 0.385f to 0xF7DFB9L, 0.462f to 0xF5DEB7L, 0.538f to 0xF5DEB7L, 0.615f to 0xF5DEB7L, 0.692f to 0xF4DFB5L, 0.769f to 0xF5DFB3L, 0.846f to 0xCAA885L, 0.923f to 0x2D1400L, 1.000f to 0x64361AL),
            ),
        ),
        Piece(
            39f, 724f, 497f, 39f, 19.5f, 0x130000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x56361FL, 0.077f to 0xBD8752L, 0.154f to 0xA19762L, 0.231f to 0xFFF285L, 0.308f to 0xFEC626L, 0.385f to 0xFCBD23L, 0.462f to 0xFBB220L, 0.538f to 0xFDAD1DL, 0.615f to 0xFAA516L, 0.692f to 0xF6A113L, 0.769f to 0xF29214L, 0.846f to 0xAA5E21L, 0.923f to 0x805032L, 1.000f to 0x401A00L),
            gloss = Gloss(53f, 733f, 362f, 5f, listOf(0.000f to 0xF8F1A1L, 0.333f to 0xFFF285L, 0.667f to 0xFCCE36L, 1.000f to 0xFEC626L)),
        ),
        Piece(
            566f, 723f, 183f, 40f, 20.0f, 0x090000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x8A5A32L, 0.077f to 0x9D5D29L, 0.154f to 0xD0C394L, 0.231f to 0xFCED79L, 0.308f to 0xFEC82AL, 0.385f to 0xFEC22AL, 0.462f to 0xFEB726L, 0.538f to 0xFDAF23L, 0.615f to 0xFAA91EL, 0.692f to 0xF8A51AL, 0.769f to 0xFBA31EL, 0.846f to 0xD07313L, 0.923f to 0x470D00L, 1.000f to 0x794A2DL),
            gloss = Gloss(581f, 733f, 106f, 4f, listOf(0.000f to 0xFFF5AAL, 0.333f to 0xFCED79L, 0.667f to 0xF3DB4AL, 1.000f to 0xFACF34L)),
        ),
        Piece(
            39f, 770f, 497f, 41f, 20.5f, 0x000000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x4F3407L, 0.077f to 0xA7764AL, 0.154f to 0x709195L, 0.231f to 0x7EE4FFL, 0.308f to 0x009DF6L, 0.385f to 0x0096FDL, 0.462f to 0x008FFCL, 0.538f to 0x0287FDL, 0.615f to 0x0080FAL, 0.692f to 0x0080FEL, 0.769f to 0x0B7DF2L, 0.846f to 0x0E58A9L, 0.923f to 0x29120FL, 1.000f to 0x723F2EL),
        ),
        Piece(
            566f, 770f, 183f, 41f, 20.5f, 0x050100L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA4734BL, 0.077f to 0x885D3CL, 0.154f to 0xA8D4D3L, 0.231f to 0x4EC0EAL, 0.308f to 0x0198FAL, 0.385f to 0x0293FEL, 0.462f to 0x008AFCL, 0.538f to 0x0081FAL, 0.615f to 0x017BFBL, 0.692f to 0x017BFBL, 0.769f to 0x0B75E3L, 0.846f to 0x0B51A2L, 0.923f to 0x27160EL, 1.000f to 0x664123L),
        ),
        Piece(
            1150f, 781f, 60f, 163f, 81.5f, 0x090000L, 3f, Kind.Box, across = true,
            listOf(0.000f to 0x3D1C09L, 0.077f to 0xB55F29L, 0.154f to 0xA64F1FL, 0.231f to 0xA14A22L, 0.308f to 0xA04921L, 0.385f to 0x99471DL, 0.462f to 0x96471CL, 0.538f to 0x96481DL, 0.615f to 0x964920L, 0.692f to 0x94451FL, 0.769f to 0x954620L, 0.846f to 0x944622L, 0.923f to 0x9F5030L, 1.000f to 0x451E0EL),
        ),
        Piece(
            1243f, 783f, 59f, 164f, 82.0f, 0x000100L, 4f, Kind.Box, across = true,
            listOf(0.000f to 0xB58C37L, 0.077f to 0xFFE548L, 0.154f to 0xFDC720L, 0.231f to 0xFFC42CL, 0.308f to 0xFDC42BL, 0.385f to 0xFDC42BL, 0.462f to 0xFEC22AL, 0.538f to 0xFEC027L, 0.615f to 0xFFBB25L, 0.692f to 0xFFBB27L, 0.769f to 0xFCB824L, 0.846f to 0xFBB723L, 0.923f to 0xF9A413L, 1.000f to 0x411B00L),
        ),
        Piece(
            1331f, 783f, 64f, 164f, 82.0f, 0x000007L, 3f, Kind.Gem, across = true,
            listOf(0.000f to 0x003252L, 0.077f to 0x10AFF7L, 0.154f to 0x03ADF9L, 0.231f to 0x02B6FCL, 0.308f to 0x0190F9L, 0.385f to 0x0091F9L, 0.462f to 0x0192FAL, 0.538f to 0x0293FEL, 0.615f to 0x0194FCL, 0.692f to 0x0096FBL, 0.769f to 0x07A3FFL, 0.846f to 0x0269DAL, 0.923f to 0x0464E1L, 1.000f to 0x023480L),
        ),
        Piece(
            1414f, 783f, 67f, 163f, 81.5f, 0x0A0004L, 4f, Kind.Berry, across = true,
            listOf(0.000f to 0x860D23L, 0.077f to 0xDF011CL, 0.154f to 0xBE1014L, 0.231f to 0xE6071DL, 0.308f to 0xFA2536L, 0.385f to 0xEA0F19L, 0.462f to 0xF32138L, 0.538f to 0xDF1229L, 0.615f to 0xDD0018L, 0.692f to 0xB50A1DL, 0.769f to 0xA50018L, 0.846f to 0x7F0616L, 0.923f to 0x96061DL, 1.000f to 0x85051EL),
        ),
        Piece(
            1514f, 787f, 54f, 164f, 82.0f, 0x040300L, 2f, Kind.Box, across = true,
            listOf(0.000f to 0x272216L, 0.077f to 0x65330CL, 0.154f to 0xE18528L, 0.231f to 0xFFF5D3L, 0.308f to 0xF6E1BAL, 0.385f to 0xF7E3B9L, 0.462f to 0xF7E3B9L, 0.538f to 0xF7E3B9L, 0.615f to 0xF7E3B9L, 0.692f to 0xF7E2BBL, 0.769f to 0xF5E2BAL, 0.846f to 0xFBDEB2L, 0.923f to 0x6B4319L, 1.000f to 0xA5552CL),
        ),
        Piece(
            40f, 818f, 496f, 40f, 20.0f, 0x050000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x422209L, 0.077f to 0xCD8459L, 0.154f to 0x42440EL, 0.231f to 0xD0FFA6L, 0.308f to 0x43C81BL, 0.385f to 0x3EC91AL, 0.462f to 0x34C114L, 0.538f to 0x2FBA10L, 0.615f to 0x29B30EL, 0.692f to 0x24B00FL, 0.769f to 0x1E9A0AL, 0.846f to 0x1D680AL, 0.923f to 0x573724L, 1.000f to 0x4D2A18L),
        ),
        Piece(
            566f, 817f, 183f, 41f, 20.5f, 0x050000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x7B572CL, 0.077f to 0x965D25L, 0.154f to 0x969F67L, 0.231f to 0x9CE96AL, 0.308f to 0x39C415L, 0.385f to 0x3CC61BL, 0.462f to 0x35BE14L, 0.538f to 0x31B915L, 0.615f to 0x2AB212L, 0.692f to 0x2CB110L, 0.769f to 0x209F14L, 0.846f to 0x236A16L, 0.923f to 0x592F1DL, 1.000f to 0x52251DL),
            gloss = Gloss(574f, 826f, 106f, 4f, listOf(0.000f to 0x969F67L, 0.333f to 0xDAFFACL, 0.667f to 0x9CE96AL, 1.000f to 0x59C52BL)),
        ),
        Piece(
            39f, 870f, 497f, 40f, 20.0f, 0x120000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x4F2813L, 0.077f to 0xC18855L, 0.154f to 0x866B60L, 0.231f to 0xFFB7B5L, 0.308f to 0xF3374AL, 0.385f to 0xF93441L, 0.462f to 0xF52B3BL, 0.538f to 0xF12739L, 0.615f to 0xED2334L, 0.692f to 0xED2131L, 0.769f to 0xD41829L, 0.846f to 0x8E141BL, 0.923f to 0x672C1BL, 1.000f to 0x481E09L),
        ),
        Piece(
            566f, 870f, 183f, 40f, 20.0f, 0x0A0000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x502C1AL, 0.077f to 0xC27E55L, 0.154f to 0x916A5BL, 0.231f to 0xFFA8A6L, 0.308f to 0xF13346L, 0.385f to 0xFB3141L, 0.462f to 0xF92B3AL, 0.538f to 0xF62738L, 0.615f to 0xF22435L, 0.692f to 0xEF2031L, 0.769f to 0xD51733L, 0.846f to 0x861120L, 0.923f to 0x67301EL, 1.000f to 0x412001L),
        ),
        Piece(
            40f, 939f, 70f, 72f, 12.0f, 0x030101L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x4A2108L, 0.077f to 0xA3582BL, 0.154f to 0x6B3921L, 0.231f to 0xF0F1ECL, 0.308f to 0xF4E0B3L, 0.385f to 0xF5DEB7L, 0.462f to 0xF8DFB5L, 0.538f to 0xF6DDB2L, 0.615f to 0xF5DCB1L, 0.692f to 0xF5DCAFL, 0.769f to 0xB49A76L, 0.846f to 0x874F32L, 0.923f to 0x874123L, 1.000f to 0x461C0CL),
            fill = Piece(
                48f, 947f, 54f, 56f, 27.0f, 0xD1753AL, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xD1753AL, 0.077f to 0x783A18L, 0.154f to 0x150000L, 0.231f to 0xFFFAF0L, 0.308f to 0xF7DEB5L, 0.385f to 0xF8DFB7L, 0.462f to 0xF8DFB5L, 0.538f to 0xF6DDB2L, 0.615f to 0xF5DCB1L, 0.692f to 0xF3DDAFL, 0.769f to 0xEDD4A7L, 0.846f to 0x0F0000L, 0.923f to 0x883E1CL, 1.000f to 0x612608L),
            ),
        ),
        Piece(
            131f, 938f, 72f, 74f, 10.0f, 0x030000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0xA9714EL, 0.077f to 0x9F4814L, 0.154f to 0x572313L, 0.231f to 0xB5855DL, 0.308f to 0x8C6D46L, 0.385f to 0x161900L, 0.462f to 0x94EB2BL, 0.538f to 0x75DF0DL, 0.615f to 0x6CE710L, 0.692f to 0x18A400L, 0.769f to 0x002600L, 0.846f to 0x703B1BL, 0.923f to 0x8B4318L, 1.000f to 0x4A2211L),
            gloss = Gloss(141f, 946f, 57f, 3f, listOf(0.000f to 0xE19159L, 0.333f to 0xEC904FL, 0.667f to 0xEC904FL, 1.000f to 0xE58B4CL)),
            fill = Piece(
                139f, 946f, 56f, 58f, 28.0f, 0xE19159L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xE19159L, 0.077f to 0x9F4814L, 0.154f to 0x2E0500L, 0.231f to 0xB5855DL, 0.308f to 0x604C29L, 0.385f to 0x161900L, 0.462f to 0xC1FF53L, 0.538f to 0x75DF0DL, 0.615f to 0x6EE50FL, 0.692f to 0x74F42BL, 0.769f to 0x1F620DL, 0.846f to 0x0C0E00L, 0.923f to 0x97461CL, 1.000f to 0x561E0AL),
            ),
        ),
        Piece(
            223f, 939f, 71f, 73f, 12.0f, 0x090000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x5B360BL, 0.077f to 0xB16224L, 0.154f to 0x824726L, 0.231f to 0xF9FAF5L, 0.308f to 0xF8E8BCL, 0.385f to 0xF9E3BEL, 0.462f to 0xF7E0B7L, 0.538f to 0xF7E0B5L, 0.615f to 0xF7E0B5L, 0.692f to 0xF7E0B5L, 0.769f to 0xCAB48BL, 0.846f to 0x87532BL, 0.923f to 0x98461FL, 1.000f to 0x532910L),
            fill = Piece(
                231f, 947f, 55f, 57f, 27.5f, 0xF89B52L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xF89B52L, 0.077f to 0xA25527L, 0.154f to 0x918B86L, 0.231f to 0xFBF5E2L, 0.308f to 0xFAE5B9L, 0.385f to 0xF9E2B9L, 0.462f to 0xF5DEB5L, 0.538f to 0xF7E0B5L, 0.615f to 0xF7E0B5L, 0.692f to 0xF7E0B5L, 0.769f to 0xDEC79CL, 0.846f to 0x0F0000L, 0.923f to 0x9B421BL, 1.000f to 0x68270BL),
            ),
        ),
        Piece(
            315f, 939f, 71f, 72f, 12.0f, 0x000000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x3D3B39L, 0.077f to 0x989694L, 0.154f to 0x787A77L, 0.231f to 0x757877L, 0.308f to 0x767676L, 0.385f to 0x767676L, 0.462f to 0x767676L, 0.538f to 0x767676L, 0.615f to 0x737373L, 0.692f to 0x727370L, 0.769f to 0x71716FL, 0.846f to 0x70706EL, 0.923f to 0x52524FL, 1.000f to 0x312B2FL),
            gloss = Gloss(324f, 945f, 53f, 7f, listOf(0.000f to 0xBAB8B6L, 0.333f to 0xA9A7A6L, 0.667f to 0x989694L, 1.000f to 0x8F908DL)),
        ),
        Piece(
            442f, 941f, 62f, 63f, 31.5f, 0x060000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA57A5EL, 0.077f to 0x8C4117L, 0.154f to 0x160000L, 0.231f to 0xFFFBF1L, 0.308f to 0xF5EACEL, 0.385f to 0xF7E4BCL, 0.462f to 0xF9E2B7L, 0.538f to 0xF6DCB4L, 0.615f to 0xF4DAB2L, 0.692f to 0xF2D9B3L, 0.769f to 0xCC9F6DL, 0.846f to 0x190400L, 0.923f to 0x893920L, 1.000f to 0x4F271BL),
            fill = Piece(
                450f, 949f, 46f, 47f, 23.0f, 0x8C4117L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0x8C4117L, 0.077f to 0x160000L, 0.154f to 0xFEFDF7L, 0.231f to 0xFAEED4L, 0.308f to 0xF7E9C5L, 0.385f to 0xF7E3B9L, 0.462f to 0xF9E2B7L, 0.538f to 0xF6DCB4L, 0.615f to 0xF5DBB3L, 0.692f to 0xF4D8B3L, 0.769f to 0xF8DAB1L, 0.846f to 0xA07F62L, 0.923f to 0x34130AL, 1.000f to 0x883A20L),
            ),
        ),
        Piece(
            529f, 940f, 65f, 64f, 32.0f, 0x0A0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x6D4928L, 0.077f to 0xCB6B38L, 0.154f to 0x7B3E31L, 0.231f to 0xFFFF99L, 0.308f to 0xF8E64DL, 0.385f to 0xFECE34L, 0.462f to 0xFCC730L, 0.538f to 0xFBB727L, 0.615f to 0xFDAF21L, 0.692f to 0xFAAC1DL, 0.769f to 0xD87804L, 0.846f to 0x320300L, 0.923f to 0x8B4525L, 1.000f to 0x451A0FL),
            fill = Piece(
                537f, 948f, 49f, 48f, 24.0f, 0xCB6B38L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xCB6B38L, 0.077f to 0x7B3E31L, 0.154f to 0xCEC477L, 0.231f to 0xF8F763L, 0.308f to 0xF8DF44L, 0.385f to 0xFDCB30L, 0.462f to 0xFAC32EL, 0.538f to 0xFBB727L, 0.615f to 0xFDAF21L, 0.692f to 0xFAAC1DL, 0.769f to 0xFFB127L, 0.846f to 0xA6601FL, 0.923f to 0x4C1B04L, 1.000f to 0x7A381EL),
            ),
        ),
        Piece(
            618f, 941f, 63f, 63f, 31.5f, 0x030000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x9A9897L, 0.077f to 0x787878L, 0.154f to 0x383838L, 0.231f to 0xEEEEEEL, 0.308f to 0xCDCDCDL, 0.385f to 0xCECECEL, 0.462f to 0xCBCBCBL, 0.538f to 0xC6C6C6L, 0.615f to 0xC4C4C4L, 0.692f to 0xC3C3C3L, 0.769f to 0xA2A2A2L, 0.846f to 0x474747L, 0.923f to 0x575855L, 1.000f to 0x232122L),
            fill = Piece(
                626f, 949f, 47f, 47f, 23.5f, 0x787878L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0x787878L, 0.077f to 0x383838L, 0.154f to 0xF7F7F7L, 0.231f to 0xCCCCCCL, 0.308f to 0xCDCDCDL, 0.385f to 0xCDCDCDL, 0.462f to 0xCBCBCBL, 0.538f to 0xC6C6C6L, 0.615f to 0xC5C5C5L, 0.692f to 0xC4C4C4L, 0.769f to 0xC1C1C1L, 0.846f to 0x808080L, 0.923f to 0x474747L, 1.000f to 0x575855L),
            ),
        ),
        Piece(
            738f, 943f, 131f, 67f, 33.5f, 0x0A0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x3E240CL, 0.077f to 0xA35A2BL, 0.154f to 0x441E08L, 0.231f to 0x4E200FL, 0.308f to 0x4F2110L, 0.385f to 0x4F210EL, 0.462f to 0x4F220CL, 0.538f to 0x4F210EL, 0.615f to 0x4D220EL, 0.692f to 0x4D220EL, 0.769f to 0x4C2013L, 0.846f to 0x4D2112L, 0.923f to 0xAA5A36L, 1.000f to 0x441A08L),
            gloss = Gloss(761f, 948f, 84f, 4f, listOf(0.000f to 0xB0804EL, 0.333f to 0xE2A365L, 0.667f to 0xB76F35L, 1.000f to 0xA35A2BL)),
            fill = Piece(
                749f, 954f, 48f, 45f, 22.5f, 0xDDC6C5L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xDDC6C5L, 0.077f to 0xF7F7ECL, 0.154f to 0xF4E0AFL, 0.231f to 0xF7E5B8L, 0.308f to 0xF6E3BBL, 0.385f to 0xF7E2BBL, 0.462f to 0xF6E2B8L, 0.538f to 0xF7E0B7L, 0.615f to 0xF3DCB1L, 0.692f to 0xF2DCAEL, 0.769f to 0xF3DDADL, 0.846f to 0xF3DDADL, 0.923f to 0xE9CC9EL, 1.000f to 0x957F60L),
            ),
        ),
        Piece(
            878f, 943f, 126f, 66f, 33.0f, 0x000300L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0x75A842L, 0.077f to 0x0D8800L, 0.154f to 0x048202L, 0.231f to 0x03A201L, 0.308f to 0x03A201L, 0.385f to 0x03A201L, 0.462f to 0x019F01L, 0.538f to 0x04A002L, 0.615f to 0x029F01L, 0.692f to 0x04A000L, 0.769f to 0x019F00L, 0.846f to 0x039C01L, 0.923f to 0x30BC21L, 1.000f to 0x0E630BL),
            gloss = Gloss(903f, 948f, 77f, 4f, listOf(0.000f to 0x75A842L, 0.333f to 0xA7F269L, 0.667f to 0x7CDE3EL, 1.000f to 0x4BBF1BL)),
        ),
        Piece(
            1016f, 943f, 131f, 67f, 33.5f, 0x010200L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x3D3D3BL, 0.077f to 0x9B9B9BL, 0.154f to 0x535255L, 0.231f to 0x555555L, 0.308f to 0x555555L, 0.385f to 0x555555L, 0.462f to 0x555555L, 0.538f to 0x555555L, 0.615f to 0x555555L, 0.692f to 0x555555L, 0.769f to 0x555555L, 0.846f to 0x555555L, 0.923f to 0x737471L, 1.000f to 0x373733L),
            gloss = Gloss(1038f, 948f, 87f, 5f, listOf(0.000f to 0xADADADL, 0.333f to 0xC6C6C6L, 0.667f to 0x9B9B9BL, 1.000f to 0x6E6E6EL)),
            fill = Piece(
                1088f, 953f, 47f, 45f, 22.5f, 0x181516L, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xA1A1A1L, 0.077f to 0xF2F2F2L, 0.154f to 0xC4C4C4L, 0.231f to 0xC5C5C5L, 0.308f to 0xC5C5C5L, 0.385f to 0xC4C4C4L, 0.462f to 0xC2C2C2L, 0.538f to 0xC1C1C1L, 0.615f to 0xC0C0C0L, 0.692f to 0xBEBEBEL, 0.769f to 0xBEBEBEL, 0.846f to 0xBDBDBDL, 0.923f to 0xB1B1B1L, 1.000f to 0xA0A0A0L),
            ),
        ),
        Piece(
            35f, 1036f, 597f, 50f, 25.0f, 0x0D0000L, 2f, Kind.Box, across = false,
            listOf(0.000f to 0x502914L, 0.077f to 0xA9632DL, 0.154f to 0x3C1607L, 0.231f to 0x3E1A09L, 0.308f to 0x431C0BL, 0.385f to 0x441D0CL, 0.462f to 0x441D0CL, 0.538f to 0x441D0CL, 0.615f to 0x441D0CL, 0.692f to 0x441D0CL, 0.769f to 0x441D0EL, 0.846f to 0x451C07L, 0.923f to 0x975029L, 1.000f to 0x542C14L),
            gloss = Gloss(51f, 1040f, 565f, 4f, listOf(0.000f to 0xBE7C51L, 0.333f to 0xC67844L, 0.667f to 0xA9632DL, 1.000f to 0x753709L)),
        ),
        Piece(
            648f, 1036f, 365f, 51f, 25.5f, 0x0B0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA5704AL, 0.077f to 0x8A4418L, 0.154f to 0x401703L, 0.231f to 0x47180AL, 0.308f to 0x461D06L, 0.385f to 0x461C0CL, 0.462f to 0x441D0CL, 0.538f to 0x441D0CL, 0.615f to 0x441D0CL, 0.692f to 0x441D0CL, 0.769f to 0x431B0DL, 0.846f to 0x421B08L, 0.923f to 0x813E19L, 1.000f to 0x603414L),
            gloss = Gloss(664f, 1040f, 332f, 4f, listOf(0.000f to 0xA5704AL, 0.333f to 0xC88354L, 0.667f to 0xB36739L, 1.000f to 0x8A4418L)),
            fill = Piece(
                658f, 1046f, 135f, 31f, 15.5f, 0xE5D7ADL, 2f, Kind.Box, across = false,
                listOf(0.000f to 0xE5D7ADL, 0.077f to 0xFDF393L, 0.154f to 0xFDD03FL, 0.231f to 0xFACE3AL, 0.308f to 0xFFCC36L, 0.385f to 0xFDC331L, 0.462f to 0xFFC030L, 0.538f to 0xFABA2CL, 0.615f to 0xFDB328L, 0.692f to 0xFEAF26L, 0.769f to 0xFEAA22L, 0.846f to 0xFAA71FL, 0.923f to 0xDE7C0FL, 1.000f to 0x7F3C0EL),
            ),
        ),
        Piece(
            1030f, 1034f, 148f, 52f, 26.0f, 0x010000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA67153L, 0.077f to 0x924C24L, 0.154f to 0x3C1607L, 0.231f to 0x431E0AL, 0.308f to 0x441D0AL, 0.385f to 0x441D0AL, 0.462f to 0x471D0EL, 0.538f to 0x471D0EL, 0.615f to 0x481F0DL, 0.692f to 0x481F0DL, 0.769f to 0x451F0CL, 0.846f to 0x4A1E0DL, 0.923f to 0x8A4B26L, 1.000f to 0x6A371EL),
            gloss = Gloss(1047f, 1039f, 114f, 4f, listOf(0.000f to 0xA67153L, 0.333f to 0xD28852L, 0.667f to 0xC4753AL, 1.000f to 0x924C24L)),
        ),
        Piece(
            1193f, 1034f, 135f, 52f, 16.0f, 0x080100L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x613D14L, 0.077f to 0xAE632EL, 0.154f to 0x401705L, 0.231f to 0x461D0AL, 0.308f to 0x481F0DL, 0.385f to 0x481F0DL, 0.462f to 0x4B1F0EL, 0.538f to 0x4A1F0EL, 0.615f to 0x48210EL, 0.692f to 0x47220EL, 0.769f to 0x471F0DL, 0.846f to 0x491E07L, 0.923f to 0x955736L, 1.000f to 0x5A2E1AL),
            gloss = Gloss(1209f, 1038f, 103f, 5f, listOf(0.000f to 0x613D14L, 0.333f to 0xC18B52L, 0.667f to 0xAE632EL, 1.000f to 0x804319L)),
        ),
        Piece(
            1335f, 1034f, 117f, 52f, 26.0f, 0x0A0200L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x5F3611L, 0.077f to 0xAA6029L, 0.154f to 0x431505L, 0.231f to 0x441D0CL, 0.308f to 0x491D0EL, 0.385f to 0x491D0EL, 0.462f to 0x481F0DL, 0.538f to 0x481F0DL, 0.615f to 0x481F0DL, 0.692f to 0x481F0DL, 0.769f to 0x471E0CL, 0.846f to 0x441F04L, 0.923f to 0x885128L, 1.000f to 0x5C2714L),
            gloss = Gloss(1352f, 1039f, 84f, 4f, listOf(0.000f to 0xBC824CL, 0.333f to 0xBA7137L, 0.667f to 0xAA6029L, 1.000f to 0x7B3C15L)),
        ),
        Piece(
            1457f, 1033f, 113f, 52f, 26.0f, 0x060000L, 4f, Kind.Box, across = false,
            listOf(0.000f to 0xC08653L, 0.077f to 0x89421BL, 0.154f to 0x3E1402L, 0.231f to 0x451D12L, 0.308f to 0x451F0CL, 0.385f to 0x451F0CL, 0.462f to 0x451F0CL, 0.538f to 0x451F0CL, 0.615f to 0x451F0CL, 0.692f to 0x431D0AL, 0.769f to 0x471C0AL, 0.846f to 0x461707L, 0.923f to 0x884723L, 1.000f to 0x683512L),
            gloss = Gloss(1474f, 1038f, 80f, 4f, listOf(0.000f to 0xC08653L, 0.333f to 0xC88042L, 0.667f to 0xB26633L, 1.000f to 0x89421BL)),
        ),
        Piece(
            35f, 1100f, 597f, 51f, 25.5f, 0x0B0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x9F6D4FL, 0.077f to 0x774628L, 0.154f to 0xCDCB91L, 0.231f to 0xF8EC73L, 0.308f to 0xFECE32L, 0.385f to 0xFBC62FL, 0.462f to 0xFCC02DL, 0.538f to 0xFCB828L, 0.615f to 0xFCAD21L, 0.692f to 0xFCA61DL, 0.769f to 0xF59D19L, 0.846f to 0x9B531FL, 0.923f to 0x713B24L, 1.000f to 0x502512L),
            gloss = Gloss(52f, 1112f, 325f, 6f, listOf(0.000f to 0xF9F6B6L, 0.333f to 0xF8EC73L, 0.667f to 0xF0DA4BL, 1.000f to 0xFFD439L)),
        ),
        Piece(
            648f, 1100f, 365f, 51f, 25.5f, 0x090000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x9C6B43L, 0.077f to 0x693F1DL, 0.154f to 0x7A8844L, 0.231f to 0xB2F35FL, 0.308f to 0x58CE25L, 0.385f to 0x47C91FL, 0.462f to 0x42C21FL, 0.538f to 0x38BA1FL, 0.615f to 0x2FB019L, 0.692f to 0x29AA13L, 0.769f to 0x137B08L, 0.846f to 0x063908L, 0.923f to 0x844B32L, 1.000f to 0x451C09L),
        ),
        Piece(
            1030f, 1100f, 148f, 51f, 25.5f, 0x0B0000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA06D47L, 0.077f to 0x7A492DL, 0.154f to 0xD3CF9DL, 0.231f to 0xF9EA74L, 0.308f to 0xFFD12DL, 0.385f to 0xFCC933L, 0.462f to 0xFBC12DL, 0.538f to 0xFABA2AL, 0.615f to 0xFBB124L, 0.692f to 0xFEAB20L, 0.769f to 0xF19519L, 0.846f to 0x935024L, 0.923f to 0x78432BL, 1.000f to 0x4E280EL),
            gloss = Gloss(1048f, 1112f, 69f, 3f, listOf(0.000f to 0xFEF9C6L, 0.333f to 0xFFF7ADL, 0.667f to 0xFFF7ADL, 1.000f to 0xF9EA74L)),
        ),
        Piece(
            1192f, 1100f, 136f, 51f, 25.5f, 0x000000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x9F6741L, 0.077f to 0x733E18L, 0.154f to 0x8FB0B0L, 0.231f to 0x37B9F4L, 0.308f to 0x009EF9L, 0.385f to 0x0096FFL, 0.462f to 0x008DFBL, 0.538f to 0x0089F9L, 0.615f to 0x0080F3L, 0.692f to 0x017DF0L, 0.769f to 0x0768D2L, 0.846f to 0x11366DL, 0.923f to 0x743D28L, 1.000f to 0x391A0EL),
        ),
        Piece(
            1336f, 1101f, 116f, 50f, 15.0f, 0x000100L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0xA56B4DL, 0.077f to 0x76462AL, 0.154f to 0xC7D395L, 0.231f to 0x8BE452L, 0.308f to 0x4AD01EL, 0.385f to 0x45C91EL, 0.462f to 0x3DC11DL, 0.538f to 0x35BA1DL, 0.615f to 0x2AB317L, 0.692f to 0x25AE13L, 0.769f to 0x23A017L, 0.846f to 0x1B5616L, 0.923f to 0x724226L, 1.000f to 0x48220CL),
        ),
        Piece(
            1457f, 1100f, 113f, 50f, 25.0f, 0x070000L, 3f, Kind.Box, across = false,
            listOf(0.000f to 0x8C5232L, 0.077f to 0x7F3F21L, 0.154f to 0xAC928EL, 0.231f to 0xE87179L, 0.308f to 0xFB3E48L, 0.385f to 0xFF3848L, 0.462f to 0xFC3341L, 0.538f to 0xF9303DL, 0.615f to 0xF62C3CL, 0.692f to 0xF12837L, 0.769f to 0xE12333L, 0.846f to 0x870B1CL, 0.923f to 0x6C2D14L, 1.000f to 0x552000L),
        ),
    )
}
