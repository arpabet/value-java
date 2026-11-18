# Consumer rules of com.arpabet:value, applied by R8 and ProGuard to an
# application that depends on the library.
#
# ValueCodec and StructCodec read the fields of the application's classes, and
# the annotations on them, by reflection. To a shrinker a class that is only
# made and read that way looks unused: it would drop the fields, the
# constructors, the annotations and the generic types the codecs go by.

-keepattributes RuntimeVisibleAnnotations,Signature

# A class the application uses that has a field named for a ValueCodec, or
# numbered for a StructCodec, keeps its constructors and those fields. They
# may be renamed, since the annotation names them.
-if class *
-keepclasseswithmembers,allowobfuscation class <1> {
    <init>(...);
    @com.arpabet.value.Name <fields>;
}
-if class *
-keepclasseswithmembers,allowobfuscation class <1> {
    <init>(...);
    @com.arpabet.value.Tag <fields>;
}

# The fields that carry another of the library's annotations stay too.
-keepclassmembers,allowobfuscation class * {
    @com.arpabet.value.Name <fields>;
    @com.arpabet.value.Tag <fields>;
    @com.arpabet.value.OmitEmpty <fields>;
    @com.arpabet.value.Sign <fields>;
    @com.arpabet.value.Unsigned <fields>;
    @com.arpabet.value.Repeated <fields>;
}

# A field without an annotation is mapped by its Java name, which a shrinker
# changes or drops: name every field of a class that goes on the wire, or
# keep the class yourself.
