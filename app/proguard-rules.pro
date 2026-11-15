# Ketvoice release: R8 optimises and shrinks the libraries (Compose, Media3, Coil…), where most of the
# speed of a Compose app is won. Ketvoice's own code is kept whole and keeps its names: it uses
# reflection (the Discord bridge), JSON field names and saved preferences, and its crash reports
# must stay readable. Nothing is renamed.
-dontobfuscate
-keep class com.ketvoice.** { *; }
-keepclassmembers class com.ketvoice.** { *; }

# The Discord Social SDK (present in CI builds only) is reached through JNI and reflection.
-keep class com.discord.** { *; }
-dontwarn com.discord.**

# Gson reads its models by reflection.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory

# Optional parts of libraries that are not in the app.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
