package dev.distillpod.android.diagnostics

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticBudgetTest {
    private fun prefs()=ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("budget-test",0).also {it.edit().clear().commit()}

    @Test fun `independent lanes admit exactly one thousand and expose overload`() {
        val p=prefs();val b=DiagnosticBudget(p){86_400_000L}
        DiagnosticBudget.Lane.entries.forEach {lane->
            repeat(lane.limit){assertTrue(b.admit(lane))}
            assertFalse(b.admit(lane))
        }
        assertEquals(1000,p.getInt("sent",0))
        assertEquals(30,p.getInt("errors",0))
        DiagnosticBudget.Lane.entries.forEach {assertEquals(1L,b.fields()[it.key+"_dropped"])}
        assertEquals("routine,critical,errors",b.exhaustion())
    }
    @Test fun `errors do not spend routine or terminal allowance`() {
        val p=prefs();val b=DiagnosticBudget(p){0L}
        repeat(30){assertTrue(b.admit(DiagnosticBudget.Lane.ERROR))}
        repeat(800){assertTrue(b.admit(DiagnosticBudget.Lane.ROUTINE))}
        repeat(160){assertTrue(b.admit(DiagnosticBudget.Lane.CRITICAL))}
        assertEquals(990,p.getInt("sent",0))
    }
    @Test fun `UTC rollover resets lanes but process restart and consent do not`() {
        val p=prefs();var now=86_399_999L
        val b=DiagnosticBudget(p){now}
        repeat(800){b.admit(DiagnosticBudget.Lane.ROUTINE)}
        assertFalse(DiagnosticBudget(p){now}.admit(DiagnosticBudget.Lane.ROUTINE))
        p.edit().putBoolean("enabled",false).commit()
        p.edit().putBoolean("enabled",true).commit()
        assertFalse(b.admit(DiagnosticBudget.Lane.ROUTINE))
        now++
        assertTrue(b.admit(DiagnosticBudget.Lane.ROUTINE))
        assertEquals(0L,b.fields()["routine_dropped"])
        assertEquals(1,p.getInt("sent",0))
    }
    @Test fun `upgrade retains legacy daily admissions even when old cap is exhausted`() {
        val p=prefs()
        p.edit().putLong("budget_day",1).putInt("sent",1000).putInt("errors",2).commit()
        val b=DiagnosticBudget(p){86_400_000L}
        assertFalse(b.admit(DiagnosticBudget.Lane.CRITICAL))
        assertEquals(1000,p.getInt("sent",0))
    }
    @Test fun `only explicit outcomes and health use reserve`() {
        assertEquals(DiagnosticBudget.Lane.ROUTINE,DiagnosticBudget.lane("operation.summary","running"))
        OperationJournal.TERMINAL.forEach {assertEquals(DiagnosticBudget.Lane.CRITICAL,DiagnosticBudget.lane("operation.summary",it))}
        assertEquals(DiagnosticBudget.Lane.CRITICAL,DiagnosticBudget.lane("distillation.finished"))
        assertEquals(DiagnosticBudget.Lane.CRITICAL,DiagnosticBudget.lane("distillation.attempt_summary"))
        assertEquals(DiagnosticBudget.Lane.ROUTINE,DiagnosticBudget.lane("transcription.stage"))
        assertEquals(DiagnosticBudget.Lane.HEALTH,DiagnosticBudget.lane("diagnostics.health"))
    }
}
