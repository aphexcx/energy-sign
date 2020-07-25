package cx.aphex.energysign.ext

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer

@Suppress("UNCHECKED_CAST")
class NonNullMutableLiveData<T>(value: T) : LiveData<T>(value) {

    override fun getValue(): T = super.getValue() as T
    public override fun setValue(value: T) = super.setValue(value)
    public override fun postValue(value: T) = super.postValue(value)
}

class NonNullMediatorLiveData<T> : MediatorLiveData<T>()

fun <T> LiveData<T>.nonNull(): NonNullMediatorLiveData<T> {
    val mediator: NonNullMediatorLiveData<T> = NonNullMediatorLiveData()
    mediator.addSource(this) { it -> it?.let { mediator.value = it } }
    return mediator
}

fun <T> NonNullMediatorLiveData<T>.observeNullable(
    owner: LifecycleOwner,
    observer: (t: T) -> Unit
) {
    observe(owner, Observer {
        it?.let(observer)
    })
}

/** Convenience function that takes an observer as a kotlin lambda */
fun <T> LiveData<T>.observeNullable(owner: LifecycleOwner, observer: (t: T?) -> Unit) {
    observe(owner, Observer { observer(it) })
}

fun <T> LiveData<T>.observeNonNull(owner: LifecycleOwner, observer: (t: T) -> Unit) {
    observe(owner, Observer {
        it?.let(observer)
    })
}
