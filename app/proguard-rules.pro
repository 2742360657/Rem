# kotlinx.serialization keeps generated serializers through its own consumer rules.

# Crash reports captured by RemLog are only readable if the release build keeps the
# attributes that tie a stack frame to a file and a line. Without these, a report says
# which method failed but not where, and the mapping file alone cannot recover it.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
