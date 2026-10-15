package com.vibetun.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Логика выбора активного набора маршрутов. Функция resolveActiveRouteSet вынесена в чистую
 * (без Android-контекста), чтобы тестировать без Robolectric/Instrumentation.
 */
class RouteSetSelectionTest {
    @Test
    fun `saved name is used when available`() {
        assertEquals("work", resolveActiveRouteSet("work", listOf("home", "work", "travel")))
    }

    @Test
    fun `falls back to first when saved is missing`() {
        assertEquals("alpha", resolveActiveRouteSet("missing", listOf("alpha", "beta")))
    }

    @Test
    fun `falls back to first when saved is blank`() {
        assertEquals("first", resolveActiveRouteSet("", listOf("first", "second")))
    }

    @Test
    fun `returns empty when no sets exist`() {
        assertEquals("", resolveActiveRouteSet("anything", emptyList()))
        assertEquals("", resolveActiveRouteSet("", emptyList()))
    }

    @Test
    fun `saved name with matching case is used`() {
        // Имена наборов — файлы, регистр имеет значение.
        assertEquals("MyRules", resolveActiveRouteSet("MyRules", listOf("MyRules", "other")))
    }

    @Test
    fun `saved name not matching any set falls back`() {
        assertEquals("aaa", resolveActiveRouteSet("deleted", listOf("aaa", "bbb")))
    }
}
