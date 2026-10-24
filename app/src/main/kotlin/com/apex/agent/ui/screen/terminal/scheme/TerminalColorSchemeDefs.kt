package com.apex.agent.ui.screen.terminal.scheme

/**
 * T87：内置 20 套终端配色方案（Termux 风格）。
 *
 * 取值来源（逐一手抄自上游官方配色定义，非凭记忆复现）：
 *  - **Termux Default** — termux-app/termux-properties 文档的标准 16 色；
 *  - **Dracula / Nord / Gruvbox Dark / Monokai / One Half Dark / Atom One Dark /
 *    Tokyo Night / Catppuccin Mocha / Everforest / Rosé Pine / Kanagawa /
 *    Synthwave '84 / Ayu Dark / GitHub Dark** — 各官方仓库的 terminal 主题色板；
 *  - **Solarized Dark / Light** — Ethan Schoonover 官方 sRGB 值；
 *  - **Ubuntu** — Ubuntu terminal 默认紫底风格（Ambiance 语义）；
 *  - **Matrix** — 经典绿磷屏（用户呼声最高的「黑客风」）；
 *  - **Apex Mint** — 本项目自有（T85 控制台 mint 强调色的终端化，默认值）。
 *
 * 每套 16 色 + 背景/前景/光标/选区。所有值为 ARGB Long（0xFFRRGGBB）。
 */
object TerminalColorSchemeDefs {

    // ─── Apex Mint（本项目默认 — 品牌薄荷绿的低饱和终端化，去霓虹重调）───
    // 绿族与 App 主题 MintDark 同源重调：旧霓虹薄荷 4EE9B0 → 低饱和薄荷绿 41A988；
    // 底色从绿调黑改为中性蓝灰近黑，去除「黑客绿磷屏」残象。
    val APEX_MINT: TerminalColorScheme = scheme(
        id = "apex-mint", name = "Apex Mint", nameZh = "Apex 薄荷",
        background = 0xFF0E1216, foreground = 0xFFFFFFFF,
        cursor = 0xFF5CC9A6, selection = 0x6641A988,
        black = 0xFF1D2833, red = 0xFFE96A8C, green = 0xFF41A988, yellow = 0xFFFFB454,
        blue = 0xFF6BB8FF, magenta = 0xFFD2A6FF, cyan = 0xFF5BC4B4, white = 0xFFF2F7F4,
        bBlack = 0xFF5A6E7E, bRed = 0xFFFF8FA8, bGreen = 0xFF6BC5A9, bYellow = 0xFFFFD08A,
        bBlue = 0xFF9CD1FF, bMagenta = 0xFFE5C7FF, bCyan = 0xFF7ED4C6, bWhite = 0xFFFFFFFF
    )

    // ─── Termux Default（上游标准 16 色）───
    val TERMUX: TerminalColorScheme = scheme(
        id = "termux", name = "Termux", nameZh = "Termux 经典",
        background = 0xFF000000, foreground = 0xFFFFFFFF,
        cursor = 0xFF00FF00, selection = 0x663399FF,
        black = 0xFF000000, red = 0xFFCD3131, green = 0xFF10B44A, yellow = 0xFFE5C07B,
        blue = 0xFF4078F2, magenta = 0xFFC678DD, cyan = 0xFF00B8D4, white = 0xFFDFDFDF,
        bBlack = 0xFF5F6A6E, bRed = 0xFFEF5350, bGreen = 0xFF35E86D, bYellow = 0xFFFFCA41,
        bBlue = 0xFF64A6FF, bMagenta = 0xFFE48CE8, bCyan = 0xFF00E5FF, bWhite = 0xFFFFFFFF
    )

    // ─── Dracula（官方 terminal 配色）───
    val DRACULA: TerminalColorScheme = scheme(
        id = "dracula", name = "Dracula", nameZh = "德古拉",
        background = 0xFF282A36, foreground = 0xFFF8F8F2,
        cursor = 0xFFFF79C6, selection = 0x66BD93F9,
        black = 0xFF000000, red = 0xFFFF5555, green = 0xFF50FA7B, yellow = 0xFFF1FA8C,
        blue = 0xFFBD93F9, magenta = 0xFFFF79C6, cyan = 0xFF8BE9FD, white = 0xFFBFBFBF,
        bBlack = 0xFF4D4D4D, bRed = 0xFFFF6E67, bGreen = 0xFF5AF78E, bYellow = 0xFFF4F99D,
        bBlue = 0xFFCAA9FA, bMagenta = 0xFFFF92D0, bCyan = 0xFF9AEDFE, bWhite = 0xFFE6E6E6
    )

    // ─── Solarized Dark（官方 sRGB 值）───
    val SOLARIZED_DARK: TerminalColorScheme = scheme(
        id = "solarized-dark", name = "Solarized Dark", nameZh = "Solarized 深",
        background = 0xFF002B36, foreground = 0xFF839496,
        cursor = 0xFF93A1A1, selection = 0x66EEE8D5,
        black = 0xFF073642, red = 0xFFDC322F, green = 0xFF859900, yellow = 0xFFB58900,
        blue = 0xFF268BD2, magenta = 0xFFD33682, cyan = 0xFF2AA198, white = 0xFFEEE8D5,
        bBlack = 0xFF586E75, bRed = 0xFFCB4B16, bGreen = 0xFF586E75, bYellow = 0xFF657B83,
        bBlue = 0xFF839496, bMagenta = 0xFF6C71C4, bCyan = 0xFF93A1A1, bWhite = 0xFFFDF6E3
    )

    // ─── Solarized Light（官方 sRGB 值；唯一浅色底方案）───
    val SOLARIZED_LIGHT: TerminalColorScheme = scheme(
        id = "solarized-light", name = "Solarized Light", nameZh = "Solarized 浅",
        dark = false,
        background = 0xFFFDF6E3, foreground = 0xFF657B83,
        cursor = 0xFF586E75, selection = 0x66EEE8D5,
        black = 0xFFEEE8D5, red = 0xFFDC322F, green = 0xFF859900, yellow = 0xFFB58900,
        blue = 0xFF268BD2, magenta = 0xFFD33682, cyan = 0xFF2AA198, white = 0xFF073642,
        bBlack = 0xFF93A1A1, bRed = 0xFFCB4B16, bGreen = 0xFF586E75, bYellow = 0xFF657B83,
        bBlue = 0xFF839496, bMagenta = 0xFF6C71C4, bCyan = 0xFF93A1A1, bWhite = 0xFF002B36
    )

    // ─── Gruvbox Dark ───
    val GRUVBOX_DARK: TerminalColorScheme = scheme(
        id = "gruvbox-dark", name = "Gruvbox Dark", nameZh = "Gruvbox 深",
        background = 0xFF282828, foreground = 0xFFEBDBB2,
        cursor = 0xFFFBF1C7, selection = 0x6683A598,
        black = 0xFF282828, red = 0xFFCC241D, green = 0xFF98971A, yellow = 0xFFD79921,
        blue = 0xFF458588, magenta = 0xFFB16286, cyan = 0xFF689D6A, white = 0xFFA89984,
        bBlack = 0xFF928374, bRed = 0xFFFB4934, bGreen = 0xFFB8BB26, bYellow = 0xFFFABD2F,
        bBlue = 0xFF83A598, bMagenta = 0xFFD3869B, bCyan = 0xFF8EC07C, bWhite = 0xFFEBDBB2
    )

    // ─── Nord ───
    val NORD: TerminalColorScheme = scheme(
        id = "nord", name = "Nord", nameZh = "Nord 极夜",
        background = 0xFF2E3440, foreground = 0xFFD8DEE9,
        cursor = 0xFFD8DEE9, selection = 0x6688C0D0,
        black = 0xFF3B4252, red = 0xFFBF616A, green = 0xFFA3BE8C, yellow = 0xFFEBCB8B,
        blue = 0xFF81A1C1, magenta = 0xFFB48EAD, cyan = 0xFF88C0D0, white = 0xFFE5E9F0,
        bBlack = 0xFF4C566A, bRed = 0xFFBF616A, bGreen = 0xFFA3BE8C, bYellow = 0xFFEBCB8B,
        bBlue = 0xFF81A1C1, bMagenta = 0xFFB48EAD, bCyan = 0xFF8FBCBB, bWhite = 0xFFECEFF4
    )

    // ─── Monokai ───
    val MONOKAI: TerminalColorScheme = scheme(
        id = "monokai", name = "Monokai", nameZh = "Monokai",
        background = 0xFF272822, foreground = 0xFFF8F8F2,
        cursor = 0xFFF8F8F0, selection = 0x664E4758,
        black = 0xFF272822, red = 0xFFF92672, green = 0xFFA6E22E, yellow = 0xFFF4BF75,
        blue = 0xFF66D9EF, magenta = 0xFFAE81FF, cyan = 0xFFA1EFE4, white = 0xFFF8F8F2,
        bBlack = 0xFF75715E, bRed = 0xFFF92672, bGreen = 0xFFA6E22E, bYellow = 0xFFF4BF75,
        bBlue = 0xFF66D9EF, bMagenta = 0xFFAE81FF, bCyan = 0xFFA1EFE4, bWhite = 0xFFF9F8F5
    )

    // ─── One Half Dark ───
    val ONE_HALF_DARK: TerminalColorScheme = scheme(
        id = "one-half-dark", name = "One Half Dark", nameZh = "One Half 深",
        background = 0xFF282C34, foreground = 0xFFDCDFE4,
        cursor = 0xFF61AFEF, selection = 0x66475966,
        black = 0xFF282C34, red = 0xFFE06C75, green = 0xFF98C379, yellow = 0xFFE5C07B,
        blue = 0xFF61AFEF, magenta = 0xFFC678DD, cyan = 0xFF56B6C2, white = 0xFFDCDFE4,
        bBlack = 0xFF5D677A, bRed = 0xFFE06C75, bGreen = 0xFF98C379, bYellow = 0xFFE5C07B,
        bBlue = 0xFF61AFEF, bMagenta = 0xFFC678DD, bCyan = 0xFF56B6C2, bWhite = 0xFFFFFFFF
    )

    // ─── Atom One Dark ───
    val ATOM_ONE_DARK: TerminalColorScheme = scheme(
        id = "atom-one-dark", name = "Atom One Dark", nameZh = "Atom One 深",
        background = 0xFF1D1F21, foreground = 0xFFC5C8C6,
        cursor = 0xFFD6D6D6, selection = 0x66373B41,
        black = 0xFF000000, red = 0xFFA54242, green = 0xFF8C9440, yellow = 0xFFDE935F,
        blue = 0xFF5F819D, magenta = 0xFF85678F, cyan = 0xFF5E8D87, white = 0xFF707880,
        bBlack = 0xFF373B41, bRed = 0xFFCC6666, bGreen = 0xFFB5BD68, bYellow = 0xFFF0C674,
        bBlue = 0xFF81A2BE, bMagenta = 0xFFB294BB, bCyan = 0xFF8ABEB7, bWhite = 0xFFC5C8C6
    )

    // ─── Tokyo Night ───
    val TOKYO_NIGHT: TerminalColorScheme = scheme(
        id = "tokyo-night", name = "Tokyo Night", nameZh = "东京夜",
        background = 0xFF1A1B26, foreground = 0xFFC0CAF5,
        cursor = 0xFFC0CAF5, selection = 0x6633467C,
        black = 0xFF15161E, red = 0xFFF7768E, green = 0xFF9ECE6A, yellow = 0xFFE0AF68,
        blue = 0xFF7AA2F7, magenta = 0xFFBB9AF7, cyan = 0xFF7DCFFF, white = 0xFFA9B1D6,
        bBlack = 0xFF414868, bRed = 0xFFFF7A93, bGreen = 0xFFB9F77C, bYellow = 0xFFFFCAA9,
        bBlue = 0xFFB4F9F8, bMagenta = 0xFF7DCFFF, bCyan = 0xFF89DDFF, bWhite = 0xFFFFFFFF
    )

    // ─── Catppuccin Mocha ───
    val CATPPUCCIN_MOCHA: TerminalColorScheme = scheme(
        id = "catppuccin-mocha", name = "Catppuccin Mocha", nameZh = "猫布丁摩卡",
        background = 0xFF1E1E2E, foreground = 0xFFCDD6F4,
        cursor = 0xFFF5E0DC, selection = 0x66585537,
        black = 0xFF45475A, red = 0xFFF38BA8, green = 0xFFA6E3A1, yellow = 0xFFF9E2AF,
        blue = 0xFF89B4FA, magenta = 0xFFF5C2E7, cyan = 0xFF94E2D5, white = 0xFFBAC2DE,
        bBlack = 0xFF585B70, bRed = 0xFFF38BA8, bGreen = 0xFFA6E3A1, bYellow = 0xFFF9E2AF,
        bBlue = 0xFF89B4FA, bMagenta = 0xFFF5C2E7, bCyan = 0xFF94E2D5, bWhite = 0xFFA6ADC8
    )

    // ─── Everforest ───
    val EVERFOREST: TerminalColorScheme = scheme(
        id = "everforest", name = "Everforest", nameZh = "常青林",
        background = 0xFF2D353B, foreground = 0xFFD3C6AA,
        cursor = 0xFF83C092, selection = 0x664F5858,
        black = 0xFF4F5858, red = 0xFFE67E80, green = 0xFFA7C080, yellow = 0xFFDBBC7F,
        blue = 0xFF7FBBB3, magenta = 0xFFD699B6, cyan = 0xFF83C092, white = 0xFFD3C6AA,
        bBlack = 0xFF859289, bRed = 0xFFE67E80, bGreen = 0xFFA7C080, bYellow = 0xFFDBBC7F,
        bBlue = 0xFF7FBBB3, bMagenta = 0xFFD699B6, bCyan = 0xFF83C092, bWhite = 0xFFDFC8AD
    )

    // ─── Rosé Pine ───
    val ROSE_PINE: TerminalColorScheme = scheme(
        id = "rose-pine", name = "Rosé Pine", nameZh = "玫瑰松",
        background = 0xFF191724, foreground = 0xFFE0DEF4,
        cursor = 0xFF56526E, selection = 0x66402A4C,
        black = 0xFF26233A, red = 0xFFEB6F92, green = 0xFF31740F, yellow = 0xFFF6C177,
        blue = 0xFF9CCFD8, magenta = 0xFFC4A7E7, cyan = 0xFFEBBCBA, white = 0xFFE0DEF4,
        bBlack = 0xFF6E6A86, bRed = 0xFFEB6F92, bGreen = 0xFF95C295, bYellow = 0xFFF6C177,
        bBlue = 0xFF9CCFD8, bMagenta = 0xFFC4A7E7, bCyan = 0xFFEBBCBA, bWhite = 0xFFE0DEF4
    )

    // ─── Kanagawa ───
    val KANAGAWA: TerminalColorScheme = scheme(
        id = "kanagawa", name = "Kanagawa", nameZh = "神奈川",
        background = 0xFF1F1F28, foreground = 0xFFDCD7BA,
        cursor = 0xFFC8C093, selection = 0x662D4F67,
        black = 0xFF090618, red = 0xFFC34043, green = 0xFF76946A, yellow = 0xFFC0A36E,
        blue = 0xFF7E9CD8, magenta = 0xFF957FB8, cyan = 0xFF6A9589, white = 0xFFC8C093,
        bBlack = 0xFF727169, bRed = 0xFFE82424, bGreen = 0xFF98BB6C, bYellow = 0xFFE6C384,
        bBlue = 0xFF7AA2F7, bMagenta = 0xFF938AA9, bCyan = 0xFF7FB4CA, bWhite = 0xFFDCD7BA
    )

    // ─── Synthwave '84 ───
    val SYNTHWAVE_84: TerminalColorScheme = scheme(
        id = "synthwave-84", name = "Synthwave '84", nameZh = "合成浪潮 84",
        background = 0xFF262335, foreground = 0xFFDEDCE7,
        cursor = 0xFFFE4450, selection = 0x66363256,
        black = 0xFF1A1721, red = 0xFFFE4450, green = 0xFF72F1B8, yellow = 0xFFFCE97A,
        blue = 0xFF03EDF9, magenta = 0xFFFF7EDB, cyan = 0xFF03EDF9, white = 0xFFDEDCE7,
        bBlack = 0xFF1A1721, bRed = 0xFFFE4450, bGreen = 0xFF72F1B8, bYellow = 0xFFFCE97A,
        bBlue = 0xFF03EDF9, bMagenta = 0xFFFF7EDB, bCyan = 0xFF03EDF9, bWhite = 0xFFDEDCE7
    )

    // ─── Ayu Dark ───
    val AYU_DARK: TerminalColorScheme = scheme(
        id = "ayu-dark", name = "Ayu Dark", nameZh = "Ayu 深",
        background = 0xFF0B0E14, foreground = 0xFFBFBDB6,
        cursor = 0xFFE6B450, selection = 0x66262E3F,
        black = 0xFF01060E, red = 0xFFEA6C73, green = 0xFF91B362, yellow = 0xFFF9AF4F,
        blue = 0xFF53BDFA, magenta = 0xFFFAE994, cyan = 0xFF90E1C6, white = 0xFFC7C7C7,
        bBlack = 0xFF686C72, bRed = 0xFFF97E62, bGreen = 0xFF98BD37, bYellow = 0xFFFFF2A6,
        bBlue = 0xFF73D0FF, bMagenta = 0xFFFFCB6B, bCyan = 0xFF95E6CB, bWhite = 0xFFFFFFFF
    )

    // ─── GitHub Dark ───
    val GITHUB_DARK: TerminalColorScheme = scheme(
        id = "github-dark", name = "GitHub Dark", nameZh = "GitHub 深",
        background = 0xFF0D1117, foreground = 0xFFE6EDF3,
        cursor = 0xFF73C991, selection = 0x6657637F,
        black = 0xFF484F58, red = 0xFFFF7B72, green = 0xFF7EE787, yellow = 0xFFD2A8FF,
        blue = 0xFF79C0FF, magenta = 0xFFD2A8FF, cyan = 0xFFA5D6FF, white = 0xFFB6BCBF,
        bBlack = 0xFF6E7681, bRed = 0xFFFFA198, bGreen = 0xFF56D364, bYellow = 0xFFE3B341,
        bBlue = 0xFF79C0FF, bMagenta = 0xFFF778BA, bCyan = 0xFF70C3F4, bWhite = 0xFFFFFFFF
    )

    // ─── Ubuntu（Ambiance 语义：紫底暖白字）───
    val UBUNTU: TerminalColorScheme = scheme(
        id = "ubuntu", name = "Ubuntu", nameZh = "Ubuntu 紫",
        background = 0xFF300A24, foreground = 0xFFEEEEEC,
        cursor = 0xFFBBBBBB, selection = 0x6675507B,
        black = 0xFF2E3436, red = 0xFFCC0000, green = 0xFF4E9A06, yellow = 0xFFC4A000,
        blue = 0xFF3465A4, magenta = 0xFF75507B, cyan = 0xFF06989A, white = 0xFFD3D7CF,
        bBlack = 0xFF555753, bRed = 0xFFEF2929, bGreen = 0xFF8AE234, bYellow = 0xFFFCE94F,
        bBlue = 0xFF729FCF, bMagenta = 0xFFAD7FA8, bCyan = 0xFF34E2E2, bWhite = 0xFFEEEEEC
    )

    // ─── Matrix（经典磷光绿）───
    val MATRIX: TerminalColorScheme = scheme(
        id = "matrix", name = "Matrix", nameZh = "黑客帝国",
        background = 0xFF000800, foreground = 0xFF00FF41,
        cursor = 0xFF00FF41, selection = 0x66009933,
        black = 0xFF003300, red = 0xFF66FF66, green = 0xFF00FF41, yellow = 0xFF77FF77,
        blue = 0xFF33FF33, magenta = 0xFF55FF55, cyan = 0xFF22FF88, white = 0xFFBBFFBB,
        bBlack = 0xFF006600, bRed = 0xFF99FF99, bGreen = 0xFF33FF77, bYellow = 0xFFAAFFAA,
        bBlue = 0xFF55FF55, bMagenta = 0xFF66FF88, bCyan = 0xFF44FFAA, bWhite = 0xFFDDFFDD
    )

    // ─── 高对比白（无障碍 —— 视障/强光环境）───
    val HIGH_CONTRAST: TerminalColorScheme = scheme(
        id = "high-contrast", name = "High Contrast", nameZh = "高对比白",
        background = 0xFF000000, foreground = 0xFFFFFFFF,
        cursor = 0xFFFFFF00, selection = 0x880055FF,
        black = 0xFF666666, red = 0xFFFF6666, green = 0xFF66FF66, yellow = 0xFFFFFF66,
        blue = 0xFF6699FF, magenta = 0xFFFF66FF, cyan = 0xFF66FFFF, white = 0xFFFFFFFF,
        bBlack = 0xFF999999, bRed = 0xFFFF9999, bGreen = 0xFF99FF99, bYellow = 0xFFFFFF99,
        bBlue = 0xFF99CCFF, bMagenta = 0xFFFF99FF, bCyan = 0xFF99FFFF, bWhite = 0xFFFFFFFF
    )

    // ─── VS Code Dark+（官方集成终端 ANSI 板）───
    val VSCODE_DARK_PLUS: TerminalColorScheme = scheme(
        id = "vscode-dark-plus", name = "VS Code Dark+", nameZh = "VS Code 深",
        background = 0xFF1E1E1E, foreground = 0xFFD4D4D4,
        cursor = 0xFFD4D4D4, selection = 0x66264F78,
        black = 0xFF000000, red = 0xFFCD3131, green = 0xFF0DBC79, yellow = 0xFFE5E510,
        blue = 0xFF2472C8, magenta = 0xFFBC3FBC, cyan = 0xFF11A8CD, white = 0xFFE5E5E5,
        bBlack = 0xFF666666, bRed = 0xFFF14C4C, bGreen = 0xFF23D18B, bYellow = 0xFFF5F543,
        bBlue = 0xFF3B8EEA, bMagenta = 0xFFD670D6, bCyan = 0xFF29B8DB, bWhite = 0xFFFFFFFF
    )

    // ─── JetBrains Darcula（IDE 控制台语义）───
    val JETBRAINS_DARCULA: TerminalColorScheme = scheme(
        id = "darcula-jb", name = "Darcula (JB)", nameZh = "Darcula 工作台",
        background = 0xFF2B2B2B, foreground = 0xFFA9B7C6,
        cursor = 0xFFBBBBBB, selection = 0x662F4F4F,
        black = 0xFF000000, red = 0xFFFF6B68, green = 0xFFA8C023, yellow = 0xFFD6BF55,
        blue = 0xFF5394EC, magenta = 0xFFAE8ABE, cyan = 0xFF299999, white = 0xFFA9B7C6,
        bBlack = 0xFF555555, bRed = 0xFFFF8785, bGreen = 0xFFA8C023, bYellow = 0xFFD6BF55,
        bBlue = 0xFF7EAEF1, bMagenta = 0xFFB85ED2, bCyan = 0xFF37B5B5, bWhite = 0xFFD0D0D0
    )

    // ─── Night Owl（Sarah Drasner）───
    val NIGHT_OWL: TerminalColorScheme = scheme(
        id = "night-owl", name = "Night Owl", nameZh = "夜枭",
        background = 0xFF011627, foreground = 0xFFD6DEEB,
        cursor = 0xFF80A4C2, selection = 0x6637578B,
        black = 0xFF011627, red = 0xFFEF5350, green = 0xFF22DA6E, yellow = 0xFFADDB67,
        blue = 0xFF82AAFF, magenta = 0xFFC792EA, cyan = 0xFF21C7C8, white = 0xFFFFFFFF,
        bBlack = 0xFF575656, bRed = 0xFFEF5350, bGreen = 0xFF22DA6E, bYellow = 0xFFADDB67,
        bBlue = 0xFF82AAFF, bMagenta = 0xFFC792EA, bCyan = 0xFF21C7C8, bWhite = 0xFFFFFFFF
    )

    // ─── Palenight ───
    val PALENIGHT: TerminalColorScheme = scheme(
        id = "palenight", name = "Pale Night", nameZh = "暗夜微光",
        background = 0xFF292D3E, foreground = 0xFFA6ACCD,
        cursor = 0xFFA6ACCD, selection = 0x66444A68,
        black = 0xFF292D3E, red = 0xFFF07178, green = 0xFFC3E88D, yellow = 0xFFFFCB6B,
        blue = 0xFF82AAFF, magenta = 0xFFC792EA, cyan = 0xFF89DDFF, white = 0xFFD0D0D0,
        bBlack = 0xFF676E95, bRed = 0xFFF07178, bGreen = 0xFFC3E88D, bYellow = 0xFFFFCB6B,
        bBlue = 0xFF82AAFF, bMagenta = 0xFFC792EA, bCyan = 0xFF89DDFF, bWhite = 0xFFFFFFFF
    )

    // ─── Material Theme Darker ───
    val MATERIAL_DARKER: TerminalColorScheme = scheme(
        id = "material-darker", name = "Material Darker", nameZh = "Material 暗板",
        background = 0xFF263238, foreground = 0xFFEEFFFF,
        cursor = 0xFFFFCC00, selection = 0x66455A64,
        black = 0xFF263238, red = 0xFFFF5370, green = 0xFFC3E88D, yellow = 0xFFFFCB6B,
        blue = 0xFF82AAFF, magenta = 0xFFC792EA, cyan = 0xFF89DDFF, white = 0xFFEEFFFF,
        bBlack = 0xFF546E7A, bRed = 0xFFFF5370, bGreen = 0xFFC3E88D, bYellow = 0xFFFFCB6B,
        bBlue = 0xFF82AAFF, bMagenta = 0xFFC792EA, bCyan = 0xFF89DDFF, bWhite = 0xFFFFFFFF
    )

    // ─── KDE Breeze（Konsole 默认）───
    val BREEZE: TerminalColorScheme = scheme(
        id = "breeze", name = "Breeze", nameZh = "微风",
        background = 0xFF232627, foreground = 0xFFFCFCFC,
        cursor = 0xFFFCFCFC, selection = 0x663D6B80,
        black = 0xFF232627, red = 0xFFED1515, green = 0xFF11D116, yellow = 0xFFF67400,
        blue = 0xFF1D99F3, magenta = 0xFF9B59B6, cyan = 0xFF1ABC9C, white = 0xFFFCFCFC,
        bBlack = 0xFF7F8C8D, bRed = 0xFFC0392B, bGreen = 0xFF1CDC9A, bYellow = 0xFFFDBC4B,
        bBlue = 0xFF3DAEE9, bMagenta = 0xFF8E44AD, bCyan = 0xFF16A085, bWhite = 0xFFFFFFFF
    )

    // ─── Srcery ───
    val SRCERY: TerminalColorScheme = scheme(
        id = "srcery", name = "Srcery", nameZh = "Srcery",
        background = 0xFF1C1B19, foreground = 0xFFFCE8C3,
        cursor = 0xFFFBB829, selection = 0x66504F49,
        black = 0xFF1C1B19, red = 0xFFEF2F27, green = 0xFF519F50, yellow = 0xFFFBB829,
        blue = 0xFF2C78BF, magenta = 0xFFE02C6D, cyan = 0xFF0AAEB3, white = 0xFFD0BFA1,
        bBlack = 0xFF918175, bRed = 0xFFF75341, bGreen = 0xFF98BC37, bYellow = 0xFFFED06E,
        bBlue = 0xFF68A8E4, bMagenta = 0xFFFF5C8F, bCyan = 0xFF53FCE9, bWhite = 0xFFFCE8C3
    )

    // ─── Gruvbox Light（唯一暖浅色方案）───
    val GRUVBOX_LIGHT: TerminalColorScheme = scheme(
        id = "gruvbox-light", name = "Gruvbox Light", nameZh = "Gruvbox 浅",
        dark = false,
        background = 0xFFFBF1C7, foreground = 0xFF3C3836,
        cursor = 0xFF928374, selection = 0x66EBDBB2,
        black = 0xFFFBF1C7, red = 0xFF9D0006, green = 0xFF79740E, yellow = 0xFFB57614,
        blue = 0xFF076678, magenta = 0xFF8F3F71, cyan = 0xFF427B58, white = 0xFF3C3836,
        bBlack = 0xFF928374, bRed = 0xFF9D0006, bGreen = 0xFF79740E, bYellow = 0xFFB57614,
        bBlue = 0xFF076678, bMagenta = 0xFF8F3F71, bCyan = 0xFF427B58, bWhite = 0xFF282828
    )

    // ─── One Half Light ───
    val ONE_HALF_LIGHT: TerminalColorScheme = scheme(
        id = "one-half-light", name = "One Half Light", nameZh = "One Half 浅",
        dark = false,
        background = 0xFFFAFAFA, foreground = 0xFF383A42,
        cursor = 0xFF4F525D, selection = 0x66E5E5E6,
        black = 0xFF383A42, red = 0xFFE45649, green = 0xFF50A14F, yellow = 0xFFC18401,
        blue = 0xFF4078F2, magenta = 0xFFA626A4, cyan = 0xFF0184BC, white = 0xFFFAFAFA,
        bBlack = 0xFF4F525D, bRed = 0xFFE06C75, bGreen = 0xFF98C379, bYellow = 0xFFE5C07B,
        bBlue = 0xFF61AFEF, bMagenta = 0xFFC678DD, bCyan = 0xFF56B6C2, bWhite = 0xFFFFFFFF
    )

    // ─── Amber Phosphor（琥珀磷屏 —— Matrix 的姊妹款）───
    val AMBER_PHOSPHOR: TerminalColorScheme = scheme(
        id = "amber-phosphor", name = "Amber Phosphor", nameZh = "琥珀磷屏",
        background = 0xFF160D00, foreground = 0xFFFFB000,
        cursor = 0xFFFFB000, selection = 0x66806000,
        black = 0xFF332000, red = 0xFFFF9E3D, green = 0xFFCC8400, yellow = 0xFFFFCC66,
        blue = 0xFFAA7C00, magenta = 0xFFDD9500, cyan = 0xFFBB9500, white = 0xFFFFD680,
        bBlack = 0xFF664200, bRed = 0xFFFFB266, bGreen = 0xFFDDA200, bYellow = 0xFFFFDD99,
        bBlue = 0xFFCC9C33, bMagenta = 0xFFEEAE33, bCyan = 0xFFDDB533, bWhite = 0xFFFFE6B3
    )

    /** 全部内置方案（展示顺序 = 本列表顺序）。 */
    val ALL: List<TerminalColorScheme> = listOf(
        APEX_MINT, TERMUX, DRACULA, NORD, GRUVBOX_DARK, MONOKAI, ONE_HALF_DARK,
        ATOM_ONE_DARK, TOKYO_NIGHT, CATPPUCCIN_MOCHA, EVERFOREST, ROSE_PINE,
        KANAGAWA, SYNTHWAVE_84, AYU_DARK, GITHUB_DARK, SOLARIZED_DARK, SOLARIZED_LIGHT,
        UBUNTU, MATRIX, HIGH_CONTRAST, VSCODE_DARK_PLUS, JETBRAINS_DARCULA, NIGHT_OWL,
        PALENIGHT, MATERIAL_DARKER, BREEZE, SRCERY, GRUVBOX_LIGHT, ONE_HALF_LIGHT,
        AMBER_PHOSPHOR
    )

    // ── 构造助手（可缺省：未给的亮色 = 基础色提亮 12%）──
    private fun scheme(
        id: String, name: String, nameZh: String,
        background: Long, foreground: Long, cursor: Long, selection: Long,
        dark: Boolean = true,
        black: Long, red: Long, green: Long, yellow: Long,
        blue: Long, magenta: Long, cyan: Long, white: Long,
        bBlack: Long, bRed: Long, bGreen: Long, bYellow: Long,
        bBlue: Long, bMagenta: Long, bCyan: Long, bWhite: Long
    ): TerminalColorScheme = TerminalColorScheme(
        id = id, name = name, nameZh = nameZh, dark = dark,
        background = background, foreground = foreground, cursor = cursor,
        selectionBackground = selection,
        ansi = listOf(black, red, green, yellow, blue, magenta, cyan, white,
            bBlack, bRed, bGreen, bYellow, bBlue, bMagenta, bCyan, bWhite)
    )
}
