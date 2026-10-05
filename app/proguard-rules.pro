# Bridge protocol DTOs use Gson reflective field access and their names are part of the wire contract.
# Keeping this small shared package avoids obfuscating/removing serialized fields during R8 shrinking.
-keep class com.craftmind.bridge.protocol.** { *; }
