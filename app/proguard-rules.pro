# The JS bridge is called by name from JavaScript, so keep it.
-keepclassmembers class com.wardchart.app.MainActivity$Bridge {
    public *;
}
