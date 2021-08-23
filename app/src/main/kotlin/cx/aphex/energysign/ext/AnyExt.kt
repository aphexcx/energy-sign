package cx.aphex.energysign.ext


import android.util.Log
import cx.aphex.energysign.ScreenLogger
import org.funktionale.memoization.memoize
import kotlin.reflect.KClass

val Any.TAG: String
    get() = memoizedTag(this::class)

/**
 * A val that stores a memoized function that takes a KClass and returns a String.
 *
 * This lets us memoize the tag calculation so it's only done once for each given KClass.
 *
 * Why do we pass the KClass in as the inner function param? Because memoize() uses that param under the hood
 * as the key to retrieve future memoized calls.
 */
private val memoizedTag: (KClass<out Any>) -> String =
    { kClass: KClass<out Any> ->
        when {
            kClass.java.isAnonymousClass -> kClass.java.firstNonLambdaEnclosingClass.simpleName
            kClass.isCompanion -> kClass.java.declaringClass?.simpleName ?: kClass.java.name
            else -> kClass.java.simpleName
        }
    }.memoize()

/**
 * Finds and returns the first non-anonymous enclosing class found by walking up the enclosingClass tree.
 */
private val Class<out Any>.firstNonLambdaEnclosingClass: Class<out Any>
    get() {
        tailrec fun walkUp(clazz: Class<out Any>): Class<out Any> {
            val enclosingClass = clazz.enclosingClass
            if (enclosingClass == null)
                return clazz
            return walkUp(enclosingClass)
        }
        return walkUp(this)
    }

/**
 * Log a crashlytics message and exception as *Non-fatal*, or logs to the Android system in **INTERNAL builds**.
 */
//fun Any.crashlyticsLogNonfatal(message: String, throwable: Throwable) {
//    logE(message, throwable)
//    FirebaseCrashlytics.getInstance().log(message)
//    FirebaseCrashlytics.getInstance().recordException(throwable)
//}

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

