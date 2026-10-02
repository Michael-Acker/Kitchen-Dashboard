package com.lifedashboard.tv.ui.theme

import android.graphics.Color

/**
 * The 13 kiosk themes. Each has a day AND a night palette.
 * Day variants are soft pastels with a clear color identity (not off-white);
 * night variants stay dark and low-glare. Colors are derived from the
 * web preview's palette set (see ts-spaces/life-dashboard).
 *
 * Palette mapping from the web tokens:
 * background=--bg, surface=--surface, surfaceVariant=--surface-raised,
 * textPrimary=--ink, textSecondary=--muted, accent=--accent,
 * accentSecondary=--sky, accountColorA=--accent.
 * accountColorB is deliberately NOT --sky: it is the OTHER day/night
 * variant's accent, so the two accounts always wear the theme's two
 * picker swatch colors (the two dots shown next to each theme name),
 * distinct from each other in both day and night variants.
 */
private fun pal(
    bg: String, surface: String, raised: String,
    ink: String, muted: String,
    accent: String, sky: String, warm: String
) = Palette(
    background = Color.parseColor(bg),
    surface = Color.parseColor(surface),
    surfaceVariant = Color.parseColor(raised),
    textPrimary = Color.parseColor(ink),
    textSecondary = Color.parseColor(muted),
    accent = Color.parseColor(accent),
    accentSecondary = Color.parseColor(sky),
    accountColorA = Color.parseColor(accent),
    accountColorB = Color.parseColor(sky)
)

val THEMES: List<AppTheme> = listOf(
    AppTheme(
        id = "orchard", name = "Orchard",
        day = pal("#AED6B4", "#D8ECDB", "#C6E2CB", "#1A2A1F", "#486A52", "#3C7A4F", "#2E7E8C", "#B2592F"),
        night = pal("#101713", "#18221C", "#202C25", "#EDF3EE", "#A9B5AD", "#7EB58A", "#80ADB5", "#DF906C")
    ),
    AppTheme(
        id = "bluehour", name = "Blue Hour",
        day = pal("#ACCFE0", "#D6E7F1", "#C2DAE8", "#16242E", "#425E6D", "#2C6E8D", "#3A6FA3", "#B0623A"),
        night = pal("#0F171A", "#172226", "#1E2C31", "#EDF3F4", "#A6B4B8", "#72B7B4", "#86A9CC", "#DC956F")
    ),
    AppTheme(
        id = "hearth", name = "Hearth",
        day = pal("#DCC7A6", "#F0E4CC", "#E7D4B8", "#2E231B", "#6C5A48", "#647A43", "#4C6E8E", "#B04A28"),
        night = pal("#18130F", "#241D18", "#2E251F", "#F1ECE5", "#B7AAA0", "#9FBA90", "#9CAEC5", "#E18461")
    ),
    AppTheme(
        id = "mulberry", name = "Mulberry",
        day = pal("#D2B6D1", "#EAD9E8", "#DEC4DC", "#2C2130", "#6C586A", "#8E4C80", "#4C7E94", "#B06834"),
        night = pal("#171216", "#221B20", "#2C232A", "#F1EBEF", "#B8AAB3", "#C38AA5", "#8EB2BC", "#DDA079")
    ),
    AppTheme(
        id = "cedar", name = "Cedar",
        day = pal("#A6CDB1", "#D3EADA", "#BFDCC6", "#1E2A22", "#486252", "#3A7A56", "#3C8296", "#AC5E32"),
        night = pal("#101512", "#19211C", "#222C25", "#EDF2EE", "#A8B4AC", "#82B397", "#87AEB0", "#DC8E69")
    ),
    AppTheme(
        id = "fjord", name = "Fjord",
        day = pal("#A6C4DE", "#D3E3F1", "#BED4E7", "#1B2733", "#425D72", "#3C6E98", "#3A8A96", "#A96B44"),
        night = pal("#10161B", "#192229", "#222E37", "#EDF2F5", "#A9B4BC", "#86ACC7", "#7FB5BE", "#D59A78")
    ),
    AppTheme(
        id = "graphite", name = "Graphite",
        day = pal("#BFC7C7", "#DDE1E1", "#CDD3D3", "#22282A", "#545E61", "#477078", "#44708F", "#A05F3D"),
        night = pal("#111415", "#1B2021", "#252B2D", "#EEF1F1", "#ABB3B5", "#91ADB1", "#8FAEC0", "#D3A079")
    ),
    AppTheme(
        id = "saffron", name = "Saffron",
        day = pal("#E4CB96", "#F5E7C6", "#ECDAAC", "#2E2A1C", "#6C6347", "#8C7224", "#4C7E8E", "#A94E2C"),
        night = pal("#17160F", "#222117", "#2C2A1D", "#F1EFE6", "#B7B2A0", "#C7B66B", "#8FAEB7", "#DC8E70")
    ),
    AppTheme(
        id = "seaglass", name = "Sea Glass",
        day = pal("#9FD1C2", "#D0E9E0", "#BBDFD1", "#182B26", "#44645B", "#2C8A70", "#388698", "#A95F40"),
        night = pal("#0E1715", "#172320", "#1F2F2A", "#EAF3F0", "#A4B6B0", "#76B8A9", "#78B4BD", "#DB947D")
    ),
    AppTheme(
        id = "terracotta", name = "Terracotta",
        day = pal("#DDB49C", "#F2DACA", "#E9C6AC", "#30211B", "#6C584C", "#6B7A46", "#48809A", "#B34826"),
        night = pal("#18120F", "#241B18", "#30231E", "#F2ECE8", "#BAA9A1", "#A9B486", "#91B0BF", "#E1846C")
    ),
    AppTheme(
        id = "periwinkle", name = "Periwinkle",
        day = pal("#B6BFEB", "#D9DDF7", "#C6CCF2", "#222845", "#545C7E", "#5866C6", "#5C8AC6", "#C0834C"),
        night = pal("#121724", "#1C2334", "#273049", "#ECEEF9", "#A4ABC9", "#9CA8E8", "#93B6D9", "#DFA878")
    ),
    AppTheme(
        id = "lavender", name = "Lavender",
        day = pal("#C2B1E7", "#DFD3F7", "#CDBFF1", "#2B2342", "#5F5476", "#8266C6", "#628CC6", "#C07A5A"),
        night = pal("#171224", "#221C33", "#2D2544", "#F1ECFA", "#B3A9C7", "#B69CE2", "#9DB9DA", "#DFA088")
    ),
    AppTheme(
        id = "mint", name = "Mint",
        day = pal("#A5DBC0", "#D2EFDC", "#BCE5C9", "#1A2C24", "#4A6456", "#339A6E", "#4C9CC2", "#C0834C"),
        night = pal("#0F1815", "#182622", "#212F2A", "#EBF5F0", "#A5B9AE", "#82CBA6", "#92C6D6", "#DCA37E")
    )
).map { theme ->
    // Account B wears the other variant's accent (see header comment).
    theme.copy(
        day = theme.day.copy(accountColorB = theme.night.accent),
        night = theme.night.copy(accountColorB = theme.day.accent)
    )
}
