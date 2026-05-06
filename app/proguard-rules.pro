# Glance / WorkManager / Ktor all keep their own consumer rules; nothing
# project-specific needed yet. Add rules here only when R8 strips
# something we explicitly want kept (typically: kotlinx.serialization
# generated code, but the plugin's @Serializable annotation handles it).
