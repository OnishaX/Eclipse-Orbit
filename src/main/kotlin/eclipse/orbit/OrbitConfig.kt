package eclipse.orbit

import java.nio.file.Path
import java.nio.file.Paths

data class OrbitConfig(
    val serverName: String,
    val httpPort: Int,
    val discoveryPort: Int,
    val dataDir: Path,
    val publicUrl: String?,
    val adminUser: String,
    val adminPassword: String?,
    val pairingTtlSeconds: Long,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = OrbitConfig(
            serverName = env["ORBIT_SERVER_NAME"] ?: "Orbit",
            httpPort = env["ORBIT_HTTP_PORT"]?.toInt() ?: 7340,
            discoveryPort = env["ORBIT_DISCOVERY_PORT"]?.toInt() ?: 7341,
            dataDir = Paths.get(env["ORBIT_DATA_DIR"] ?: "data"),
            publicUrl = env["ORBIT_PUBLIC_URL"]?.trimEnd('/')?.ifBlank { null },
            adminUser = env["ORBIT_ADMIN_USER"] ?: "admin",
            adminPassword = env["ORBIT_ADMIN_PASSWORD"]?.ifBlank { null },
            pairingTtlSeconds = env["ORBIT_PAIRING_TTL_SECONDS"]?.toLong() ?: 600,
        )
    }
}
