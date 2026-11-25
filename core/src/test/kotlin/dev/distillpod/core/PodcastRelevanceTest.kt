package dev.distillpod.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #307: suggested podcasts rank and explain themselves from their own metadata, never from a shared search reason. */
class PodcastRelevanceTest {
    // Channel metadata as published by the real feeds in the reported `dog breeds` run (directory categories are localized).
    private val audiobooks = PodcastMetadata("Never A Dull Moment With Audiobooks", "Book Buzz",
        "listen to full audiobook for free on Audible : Just sign up and enjoy unlimited podcasts with Audible standard. They've got what everyone's listening to, Bestsellers, New releases.",
        "Educação para crianças")
    private val dogTalk = PodcastMetadata("Dog Talk", "Heather Parsons",
        "I've been in the animal industry....forever. I've travelled all over with my dogs, competed in a ton of sports. Join me on our adventures and we will talk about pet care, dog food, travel, sports....and everything dogs.",
        "Animais")
    private val dogBreeds = PodcastMetadata("Dog Breeds", "Melany Benavides Porras",
        "Some information about history, characteristics, personality and coexistence of German Shepherds", "Animais")

    private fun order(interests: String, vararg podcasts: PodcastMetadata, searches: List<String> = emptyList()) =
        PodcastRelevance.rank(interests, searches, podcasts.toList()) { it }.map { it.first.title }

    @Test fun `dog breeds ranks the dog podcasts ahead of an audiobook feed that matched only through an episode`() {
        assertEquals(listOf("Dog Breeds", "Dog Talk", "Never A Dull Moment With Audiobooks"),
            order("dog breeds", audiobooks, dogTalk, dogBreeds, searches = listOf("dog breed traits and care")))
    }

    @Test fun `each reason names what that podcast's own metadata mentions`() {
        val searches = listOf("dog breed traits and care")
        assertEquals("Title mentions “dog” and “breeds”.", PodcastRelevance.match("dog breeds", searches, dogBreeds).reason)
        assertEquals("Title and description mention “dog”; description mentions “care”.", PodcastRelevance.match("dog breeds", searches, dogTalk).reason)
    }

    @Test fun `a podcast with no grounded match has no reason and no score`() {
        val match = PodcastRelevance.match("dog breeds", listOf("dog breed traits and care"), audiobooks)
        assertEquals(0, match.score); assertEquals("", match.reason)
    }

    @Test fun `the user's own interests outweigh words that only the generated search added`() {
        val canine = PodcastMetadata("Canine Genetics Weekly")
        val breeds = PodcastMetadata("Breeds Explained")
        assertEquals(listOf("Breeds Explained", "Canine Genetics Weekly"), order("dog breeds", canine, breeds, searches = listOf("canine genetics")))
        assertEquals("Title mentions “canine” and “genetics”.", PodcastRelevance.match("dog breeds", listOf("canine genetics"), canine).reason)
    }

    @Test fun `plurals, accents and case match while filler words and partial words do not`() {
        val portuguese = PodcastMetadata("Raca de Cachorros", description = "Tudo sobre cães")
        assertEquals("Title mentions “raças” and “cachorro”.", PodcastRelevance.match("Raças de cachorro", emptyList(), portuguese).reason)
        assertEquals(0, PodcastRelevance.match("the podcast about dogs", emptyList(), PodcastMetadata("The Dogma Podcast", "About Us")).score)
    }

    @Test fun `title matches outrank description-only matches and ties keep the directory order`() {
        val title = PodcastMetadata("Science Hour"); val description = PodcastMetadata("Weekly Hour", description = "Science news")
        val first = PodcastMetadata("Science A"); val second = PodcastMetadata("Science B")
        assertEquals(listOf("Science Hour", "Weekly Hour"), order("science", description, title))
        assertEquals(listOf("Science A", "Science B"), order("science", first, second))
        assertEquals(listOf("Science B", "Science A"), order("science", second, first))
    }

    @Test fun `category and author matches are explained by their field`() {
        val match = PodcastRelevance.match("animais", emptyList(), dogBreeds)
        assertEquals("Category mentions “animais”.", match.reason); assertTrue(match.score > 0)
        assertEquals("Author mentions “parsons”.", PodcastRelevance.match("heather parsons", emptyList(), PodcastMetadata("Show", "Kim Parsons")).reason)
    }
}
