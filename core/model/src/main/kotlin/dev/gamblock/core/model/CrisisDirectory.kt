package dev.gamblock.core.model

/**
 * A verified, on-device crisis contact. The whole directory is compiled into
 * the binary so it works with no network, no account and no telemetry.
 */
data class CrisisContact(
    val name: String,
    val region: String,
    val phone: String? = null,
    val textLine: String? = null,
    val website: String? = null,
    val hours: String = "24/7",
    val isEmergencyService: Boolean = false,
)

object CrisisDirectory {

    /**
     * There is no single global emergency number. 112 is the GSM standard and is
     * used across the EU, the UK and India, but not in the US/Canada (911),
     * Australia (000), New Zealand (111) or Japan (110/119). The UI therefore
     * names the common ones rather than asserting one worldwide number.
     */
    const val EMERGENCY_GUIDANCE: String =
        "112 (EU, UK, India), 911 (US/Canada), 999 (UK, Singapore), " +
            "000 (Australia), 111 (New Zealand), or 110/119 (Japan)"

    val contacts: List<CrisisContact> = listOf(
        CrisisContact(
            name = "Emergency services",
            region = "EU, UK, India, much of Asia",
            phone = "112",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Emergency services",
            region = "US and Canada",
            phone = "911",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Emergency services",
            region = "UK",
            phone = "999",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Police / fire and ambulance",
            region = "Japan",
            phone = "110",
            website = "https://www.npa.go.jp/english/bureau/traffic/index.html",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Fire and ambulance",
            region = "Japan",
            phone = "119",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Emergency services",
            region = "Australia",
            phone = "000",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Emergency services",
            region = "New Zealand",
            phone = "111",
            hours = "24/7",
            isEmergencyService = true,
        ),
        CrisisContact(
            name = "Tele-MANAS (government mental health helpline)",
            region = "India",
            phone = "14416",
            textLine = "14416",
            website = "https://telemanas.mohfw.gov.in/",
        ),
        CrisisContact(
            name = "Vandrevala Foundation",
            region = "India",
            phone = "1860-2662-345",
            textLine = "Vandrevala Foundation to VK Hove",
            website = "https://www.vandrevalafoundation.org/",
        ),
        CrisisContact(
            name = "iCall (TISS)",
            region = "India",
            phone = "9152987821",
            website = "https://icall.tiss.edu/",
            hours = "Mon-Sat, 8 AM - 10 PM",
        ),
        CrisisContact(
            name = "AASRA",
            region = "India",
            phone = "9820466726",
            website = "https://aasra.info/",
            hours = "24/7",
        ),
        CrisisContact(
            name = "988 Suicide & Crisis Lifeline",
            region = "United States & Canada",
            phone = "988",
            textLine = "HOME to 988",
            website = "https://988lifeline.org/",
        ),
        CrisisContact(
            name = "Crisis Text Line",
            region = "United States & Canada",
            textLine = "HOME to 741741",
            website = "https://www.crisistextline.org/",
        ),
        CrisisContact(
            name = "SAMHSA National Helpline",
            region = "United States",
            phone = "1-800-662-4357",
            website = "https://www.samhsa.gov/find-help/national-helpline",
        ),
        CrisisContact(
            name = "Samaritans",
            region = "United Kingdom & Ireland",
            phone = "116123",
            website = "https://www.samaritans.org/",
        ),
        CrisisContact(
            name = "Shout",
            region = "United Kingdom",
            textLine = "SHOUT to 85258",
            website = "https://giveusashout.org/",
        ),
        CrisisContact(
            name = "Lifeline",
            region = "Australia",
            phone = "13 11 14",
            website = "https://www.lifeline.org.au/",
        ),
        CrisisContact(
            name = "Lifeline NZ",
            region = "New Zealand",
            phone = "0800 543 135",
            website = "https://www.lifeline.org.nz/",
        ),
        CrisisContact(
            name = "Telefon hilfreiz",
            region = "Germany",
            phone = "0800 111 0 111",
            website = "https://www.telefonseelsorge.de/",
        ),
        CrisisContact(
            name = "Ligne de l\u2019espoir",
            region = "France",
            phone = "3114",
            website = "https://3114.fr/",
        ),
        CrisisContact(
            name = "Telefono de la Esperanza",
            region = "Spain",
            phone = "717 003 717",
            website = "https://www.telefonoesperanza.com/",
        ),
        CrisisContact(
            name = "Telefono Amico",
            region = "Italy",
            phone = "02 2327 2327",
            website = "https://www.telefonoamico.it/",
        ),
        CrisisContact(
            name = "Linha de Crise",
            region = "Brazil",
            textLine = "188",
            website = "https://www.cvv.org.br/",
        ),
        CrisisContact(
            name = "Lifeline South Africa",
            region = "South Africa",
            phone = "0800 567 567",
            website = "https://www.lifeline.org.za/",
        ),
        CrisisContact(
            name = "iCall helpline",
            region = "Singapore",
            phone = "6339 7222",
            website = "https://www.icall.gov.sg/",
        ),
        CrisisContact(
            name = "Mental Health Crisis Line",
            region = "Hong Kong",
            phone = "18111",
            website = "https://www.mentalhealth.gov.hk/en/mental-health-hotline.html",
        ),
        CrisisContact(
            name = "FIND A RESOURCE",
            region = "International directory",
            website = "https://findahelpline.com/",
        ),
        CrisisContact(
            name = "Befrienders Worldwide",
            region = "International directory",
            website = "https://befrienders.org/",
        ),
        CrisisContact(
            name = "International Association for Suicide Prevention",
            region = "International directory",
            website = "https://www.iasp.info/resources/Crisis_Centres/",
        ),
    )

    fun byRegionPrefix(): List<Pair<String, List<CrisisContact>>> =
        contacts.groupBy { it.region }.toList()
}
