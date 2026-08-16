package io.multiverseportals.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShapeHasherKeywordTest {

    @Test
    void englishTypes() {
        assertEquals("multi", ShapeHasher.parseType("Portal"));
        assertEquals("multi", ShapeHasher.parseType("[Multi]"));
        assertEquals("away", ShapeHasher.parseType("Away"));
        assertEquals("to", ShapeHasher.parseType("[To]"));
        assertEquals("pair", ShapeHasher.parseType("Pair"));
    }

    @Test
    void russianTypes() {
        assertEquals("multi", ShapeHasher.parseType("Портал"));
        assertEquals("multi", ShapeHasher.parseType("[портал]"));
        assertEquals("multi", ShapeHasher.parseType("Мульти"));
        assertEquals("away", ShapeHasher.parseType("Авей"));
        assertEquals("away", ShapeHasher.parseType("Биом"));
        assertEquals("to", ShapeHasher.parseType("К"));
        assertEquals("to", ShapeHasher.parseType("На"));
        assertEquals("to", ShapeHasher.parseType("Сервер"));
        assertEquals("pair", ShapeHasher.parseType("Пара"));
        assertEquals("multi", ShapeHasher.parseType("Случайный"));
    }

    @Test
    void chineseTypes() {
        assertEquals("multi", ShapeHasher.parseType("传送门"));
        assertEquals("multi", ShapeHasher.parseType("【传送门】"));
        assertEquals("multi", ShapeHasher.parseType("随机"));
        assertEquals("away", ShapeHasher.parseType("异界"));
        assertEquals("away", ShapeHasher.parseType("群系"));
        assertEquals("to", ShapeHasher.parseType("前往"));
        assertEquals("pair", ShapeHasher.parseType("配对"));
    }

    @Test
    void destLineUnderPortal() {
        assertEquals("away", ShapeHasher.parseDestKind("Away"));
        assertEquals("away", ShapeHasher.parseDestKind("авей"));
        assertEquals("away", ShapeHasher.parseDestKind("异界"));
        assertEquals("multi", ShapeHasher.parseDestKind("Портал"));
        assertEquals("multi", ShapeHasher.parseDestKind("传送门"));
        assertEquals("multi", ShapeHasher.parseDestKind("случайный"));
        assertEquals("to", ShapeHasher.parseDestKind("на"));
        assertEquals("pair", ShapeHasher.parseDestKind("пара"));
        assertEquals("", ShapeHasher.parseDestKind(""));
        assertNull(ShapeHasher.parseDestKind("play.example.com"));
    }

    @Test
    void yoAndFullwidth() {
        assertEquals("multi", ShapeHasher.parseType("Портал"));
        assertEquals("multi", ShapeHasher.parseType("Ｐｏｒｔａｌ"));
    }
}
