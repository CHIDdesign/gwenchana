package com.askthecar.qa.bench

/** Marks a test that needs > ~30 s of real time. run.sh skips these when QA_SLOW=0. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Slow(val why: String)
