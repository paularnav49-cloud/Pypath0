package com.pypath.app

import com.pypath.app.data.content.CourseParser
import com.pypath.app.data.model.LessonBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the generated JSON in assets/course against the course rules. */
class CourseContentTest {
    private val course = loadTestCourse()
    private val subs = course.levels.flatMap { it.subLevels }
    private val lessons = subs.filterNot { it.isProject }
    private val newLessons = course.levels.filter { it.number >= 3 }.flatMap { it.subLevels }.filterNot { it.isProject }

    @Test fun indexListsSevenLevelFilesThatAllParse() {
        val dir = File("src/main/assets/course")
        val index = CourseParser.parse(File(dir, "course.json").readText())
        assertEquals(2, index.schemaVersion)
        assertEquals(7, index.levelFiles.size)
        index.levelFiles.forEach { assertNotNull(it, CourseParser.parseLevel(File(dir, it).readText())) }
        assertEquals((1..7).toList(), course.levels.map { it.number })
        assertTrue("every level is available", course.levels.all { it.available })
    }

    @Test fun idsAreUniqueEverywhere() {
        val ids = course.levels.map { it.id } + subs.map { it.id } +
            lessons.flatMap { s -> s.quiz!!.questions.map { it.id } } +
            subs.flatMap { s -> s.stages.flatMap { listOf(it.id, it.question.id) } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun questionIdsFollowThePattern() {
        newLessons.forEach { s ->
            s.quiz!!.questions.forEachIndexed { i, q -> assertEquals("${s.id}q${i + 1}", q.id) }
        }
    }

    @Test fun everyQuestionHasFourOptionsAndAValidAnswer() {
        val all = lessons.flatMap { it.quiz!!.questions } + subs.flatMap { s -> s.stages.map { it.question } }
        all.forEach {
            assertEquals("${it.id} options", 4, it.options.size)
            assertEquals("${it.id} distinct options", 4, it.options.toSet().size)
            assertTrue("${it.id} correctIndex", it.correctIndex in 0..3)
            assertTrue("${it.id} explanation", it.explanation.isNotBlank())
        }
    }

    @Test fun practicalContractIdsStillExistWithTheirMeaning() {
        listOf("l1s1", "l1s2", "l1s3", "l1s4", "l2s1", "l2s2", "l2s3", "l3s1", "l3s2", "l5s3").forEach { id ->
            assertTrue(id, subs.any { it.id == id })
        }
        assertEquals("for loops", subs.first { it.id == "l3s1" }.title)
        assertEquals("while loops", subs.first { it.id == "l3s2" }.title)
        assertEquals("List methods", subs.first { it.id == "l5s3" }.title)
    }

    @Test fun pageAndQuestionCountsVary() {
        val pages = newLessons.map { it.lesson!!.pages.size }
        val mcqs = newLessons.map { it.quiz!!.questions.size }
        assertTrue(pages.all { it in 2..6 })
        assertTrue(mcqs.all { it in 3..8 })
        assertTrue("page counts not all equal", pages.toSet().size > 1)
        assertTrue("MCQ counts not all equal", mcqs.toSet().size > 1)
        assertFalse("consecutive page counts", pages.zipWithNext().any { (a, b) -> a == b })
        assertFalse("consecutive MCQ counts", mcqs.zipWithNext().any { (a, b) -> a == b })
        assertTrue("sub-levels per level vary", course.levels.map { it.subLevels.size }.toSet().size > 1)
    }

    @Test fun correctAnswersAreSpreadOut() {
        newLessons.forEach { s ->
            val pos = s.quiz!!.questions.map { it.correctIndex }
            assertFalse("${s.id} three in a row: $pos", pos.windowed(3).any { it.toSet().size == 1 })
            val counts = (0..3).map { k -> pos.count { it == k } }
            assertTrue("${s.id} balanced: $pos", counts.max() - counts.min() <= 1)
            assertTrue("${s.id} passPercent", s.quiz!!.passPercent == 60)
        }
    }

    @Test fun everyNewLessonHasCodeAndACommonMistakeTip() {
        newLessons.forEach { s ->
            val blocks = s.lesson!!.pages.flatMap { it.blocks }
            assertTrue("${s.id} code with output", blocks.any { it is LessonBlock.Code && it.output != null })
            assertTrue("${s.id} common mistake", blocks.any { it is LessonBlock.Tip && it.title == "Common mistake" })
        }
    }
}
