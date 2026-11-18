package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StuckJobsTest {

    @Test
    void aClientIsHeldUntilEveryStuckJobOfItsEnds() {
        StuckJobs stuck = new StuckJobs();
        stuck.stuck("10.0.0.1", "aa");
        stuck.stuck("10.0.0.1", "bb");

        assertEquals(2, stuck.count());
        assertTrue(stuck.holds("10.0.0.1"));
        assertFalse(stuck.holds("10.0.0.2"));

        stuck.ended("10.0.0.1", "aa");
        assertTrue(stuck.holds("10.0.0.1"));
        stuck.ended("10.0.0.1", "bb");
        assertFalse(stuck.holds("10.0.0.1"));
        assertEquals(0, stuck.count());
    }

    @Test
    void anInputThatGotStuckStaysRefusedAfterItEnds() {
        StuckJobs stuck = new StuckJobs();
        stuck.stuck("10.0.0.1", "aa");
        stuck.ended("10.0.0.1", "aa");

        assertTrue(stuck.refused("aa"));
        assertFalse(stuck.refused("bb"));
        assertFalse(stuck.refused(null));
    }

    @Test
    void remembersABoundedNumberOfInputs() {
        StuckJobs stuck = new StuckJobs();
        for (int i = 0; i <= StuckJobs.REMEMBERED_INPUTS; i++) {
            stuck.stuck("c", Integer.toString(i));
            stuck.ended("c", Integer.toString(i));
        }

        assertFalse(stuck.refused("0"));
        assertTrue(stuck.refused("1"));
        assertTrue(stuck.refused(Integer.toString(StuckJobs.REMEMBERED_INPUTS)));
    }

    @Test
    void anInputStillStuckIsNeverForgotten() {
        StuckJobs stuck = new StuckJobs();
        stuck.stuck("10.0.0.1", "held");
        for (int i = 0; i <= StuckJobs.REMEMBERED_INPUTS; i++) {
            stuck.stuck("c", Integer.toString(i));
            stuck.ended("c", Integer.toString(i));
        }

        assertTrue(stuck.refused("held"));
    }

    @Test
    void anInputSeenAgainMovesToTheBackOfTheQueue() {
        StuckJobs stuck = new StuckJobs();
        stuck.stuck("c", "first");
        stuck.ended("c", "first");
        for (int i = 1; i < StuckJobs.REMEMBERED_INPUTS; i++) {
            stuck.stuck("c", Integer.toString(i));
            stuck.ended("c", Integer.toString(i));
        }
        stuck.stuck("c", "first");
        stuck.ended("c", "first");
        stuck.stuck("c", "new");
        stuck.ended("c", "new");

        assertTrue(stuck.refused("first"));
        assertFalse(stuck.refused("1"));
    }
}
