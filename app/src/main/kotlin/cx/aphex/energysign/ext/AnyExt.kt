package cx.aphex.energysign.ext


import android.util.Log
import arrow.core.MemoizedDeepRecursiveFunction
import com.google.firebase.crashlytics.FirebaseCrashlytics
import cx.aphex.energysign.ScreenLogger
import kotlin.reflect.KClass

/**
 * Extension property that provides a standardized tag for logging purposes.
 *
 * This property is commonly used with Android's Log utility methods to
 * provide consistent class identification in log output.
 *
 * @return A String representing the class name appropriate for logging
 */
val Any.TAG: String
    get() = memoizedTag(this::class)

/**
 * Memoized recursive function that derives appropriate tag names from Kotlin classes.
 *
 * This implementation uses Arrow's MemoizedDeepRecursiveFunction for efficient caching
 * of computed values, ensuring each class type is processed only once, regardless of
 * how many instances are created. That's why we pass in kClass as the inner function param;
 * it's used as the key to retrieve future memoized calls.
 *
 * The function handles three specific cases:
 * 1. Anonymous classes - recursively finds the enclosing non-anonymous class name
 * 2. Companion objects - returns the name of the declaring class
 * 3. Regular classes - returns the simple class name
 *
 * @param kClass The Kotlin class to derive a tag from
 * @return A String tag appropriate for logging
 */
private val memoizedTag = MemoizedDeepRecursiveFunction<KClass<out Any>, String> { kClass ->
    when {
        kClass.java.isAnonymousClass -> {
            // For anonymous classes, recursively find the enclosing class
            val enclosingClass = kClass.java.enclosingClass
            if (enclosingClass != null) {
                callRecursive(enclosingClass.kotlin)
            } else {
                kClass.java.name // Fallback if no enclosing class
            }
        }

        kClass.isCompanion ->
            kClass.java.declaringClass?.simpleName ?: kClass.java.name

        else ->
            kClass.java.simpleName
    }
}

/**
 * Log a crashlytics message and exception as *Non-fatal*, or logs to the Android system in **INTERNAL builds**.
 */
fun Any.crashlyticsLogNonfatal(message: String, throwable: Throwable) {
    logE(message, throwable)
    FirebaseCrashlytics.getInstance().log(message)
    FirebaseCrashlytics.getInstance().recordException(throwable)
}

///**
// * Log a crashlytics exception as *Non-fatal*, & logs to the Android system in **INTERNAL builds**.
// */
//fun Any.crashlyticsLogNonfatal(throwable: Throwable) {
//    logE(throwable)
//    FirebaseCrashlytics.getInstance().recordException(throwable)
//}

//
//fun Any.crashlyticsLog(message: String) {
//    when {
//        INTERNAL_BUILD -> logD(message)
//        FirebaseCrashlytics.getInstance().log(message)
//    }
//}

fun Any.logE(message: String, throwable: Throwable) {
    ScreenLogger.log("🚨E🚨 $message")
    Log.e(this.TAG, message, throwable)
}

fun Any.logE(message: String) {
    ScreenLogger.log("🚨E🚨 $message")
    Log.e(this.TAG, message)
}

//fun Any.logE(throwable: Throwable) {
//    Log.e(this.TAG, "", throwable)
//}

fun Any.logW(message: String, throwable: Throwable) {
    ScreenLogger.log("⚠️W⚠️ $message")

    Log.w(this.TAG, message, throwable)
}

fun Any.logW(message: String) {
    ScreenLogger.log("⚠️W⚠️ $message")
    Log.w(this.TAG, message)
}

fun Any.logD(message: String) {
    ScreenLogger.log(message)
    Log.d(this.TAG, message)
}

fun Any.logI(message: String) {
    ScreenLogger.log(message)
    Log.i(this.TAG, message)
}

fun Any.logV(message: String) {
    ScreenLogger.log(message)
    Log.v(this.TAG, message)
}

