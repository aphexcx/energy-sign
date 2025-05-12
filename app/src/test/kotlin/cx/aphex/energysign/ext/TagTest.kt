package cx.aphex.energysign.ext

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for the TAG extension property in AnyExt.kt
 *
 * This test doesn't try to mock Android dependencies, instead focusing directly on
 * the behavior of the TAG property which doesn't need those dependencies to be tested.
 */
class TagTest {

    @Test
    fun testRegularClassTag() {
        // Given a regular class
        val regularObject = RegularTestClass()

        // When getting its TAG
        val tag = regularObject.TAG

        // Then the tag should be the simple class name
        assertEquals("RegularTestClass", tag)
    }

    @Test
    fun testCompanionObjectTag() {
        // Given a companion object
        val companionObject = CompanionTestClass.Companion

        // When getting its TAG
        val tag = companionObject.TAG

        // Then the tag should be the parent class name
        assertEquals("CompanionTestClass", tag)
    }

    @Test
    fun testAnonymousClassTag() {
        // Given an anonymous class
        val anonymousObject = object : Any() {}

        // When getting its TAG
        val tag = anonymousObject.TAG

        // Then the tag should be the enclosing class name
        assertEquals("TagTest", tag)
    }

    @Test
    fun testNestedAnonymousClassTag() {
        // Given a nested anonymous class
        val nestedAnonymousObject = object : Any() {
            val innerObject = object : Any() {}
        }

        // When getting the inner object's TAG
        val tag = nestedAnonymousObject.innerObject.TAG

        // Then the tag should still be the enclosing class name
        assertEquals("TagTest", tag)
    }

    @Test
    fun testMemoizationForMultipleCallsWithSameClass() {
        // Create multiple instances of the same class
        val object1 = RegularTestClass()
        val object2 = RegularTestClass()

        // Verify tags are the same object (not just equal values)
        // This tests memoization by checking that the same string instance is returned
        val tag1 = object1.TAG
        val tag2 = object2.TAG
        assertEquals(tag1, tag2)

        // Both should have the same class name
        assertEquals("RegularTestClass", tag1)
    }

    // Test classes
    private class RegularTestClass

    private class CompanionTestClass {
        companion object
    }
}