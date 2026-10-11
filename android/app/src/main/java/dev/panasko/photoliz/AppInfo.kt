package dev.panasko.photoliz

/** Static product information shown in MENU → DESCRIPTION. */
object AppInfo {
    const val NAME = "Photoliz"
    const val DEVELOPER = "radacuxiv"
    const val WEBSITE = "panasko.dev"
    const val WEBSITE_URL = "https://panasko.dev"
    const val MAIL = "mail@panasko.dev"
    const val TAGLINE =
        "Photoliz treats your phone as a weirdly designed plain digital camera, " +
            "not a machine that decides how your pics look."

    class Credit(val name: String, val role: String, val link: String?)

    val CREDITS = listOf(
        Credit("radacuxiv", "Developer", WEBSITE),
        Credit("Clemems Montiel", "Photographer, consulting", null),
    )

    /** Folder names used for saved media. */
    const val MEDIA_DIR = "Photoliz"
    const val DNG_SUBDIR = "DNG"
}
