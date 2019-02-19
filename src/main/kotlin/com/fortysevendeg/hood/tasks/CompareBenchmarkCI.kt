package com.fortysevendeg.hood.tasks

import arrow.core.toOption
import arrow.effects.IO
import arrow.effects.fix
import arrow.effects.handleErrorWith
import arrow.effects.instances.io.monad.monad
import arrow.instances.list.foldable.nonEmpty
import com.fortysevendeg.hood.*
import com.fortysevendeg.hood.github.GithubCommentIntegration
import com.fortysevendeg.hood.github.GithubCommon
import com.fortysevendeg.hood.syntax.prettyPrintResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import java.io.File

open class CompareBenchmarkCI : DefaultTask() {

  @get:InputFile
  var previousBenchmarkPath: File =
    project.objects.fileProperty().asFile.getOrElse(File("master.csv"))
  @get:InputFiles
  var currentBenchmarkPath: List<File> =
    project.objects.listProperty(File::class.java).getOrElse(emptyList())
  @get:Input
  var keyColumnName: String = project.objects.property(String::class.java).getOrElse("Benchmark")
  @get:Input
  var compareColumnName: String = project.objects.property(String::class.java).getOrElse("Score")
  @get:Input
  var threshold: Int = project.objects.property(Int::class.java).getOrElse(50)
  @get:Input
  var token: String? = project.objects.property(String::class.java).orNull
  @get:Input
  var outputToFile: Boolean = project.objects.property(Boolean::class.java).getOrElse(false)
  @get:Input
  var outputPath: String =
    project.objects.property(String::class.java).getOrElse("./hood/comparison")
  @get:Input
  var outputFormat: String =
    project.objects.property(String::class.java).getOrElse("md")

  private fun getWrongResults(result: List<BenchmarkComparison>): List<BenchmarkComparison> =
    result.filter { it.result::class == BenchmarkResult.ERROR::class || it.result::class == BenchmarkResult.FAILED::class }

  private fun compareCI(info: GhInfo, commitSha: String, pr: Int) = IO.monad().binding {

    GithubCommentIntegration.setPendingStatus(
      info,
      commitSha
    ).bind()

    val result: List<BenchmarkComparison> = Comparator.compareCsv(
      previousBenchmarkPath,
      currentBenchmarkPath,
      threshold,
      keyColumnName,
      compareColumnName
    ).bind()

    val previousComment =
      GithubCommentIntegration.getPreviousCommentId(info, pr).bind()
    val cleanResult =
      previousComment.fold({ IO { true } }) { GithubCommentIntegration.deleteComment(info, it) }
        .bind()

    val commentResult = GithubCommentIntegration.createComment(info, pr, result).bind()

    if (commentResult && cleanResult) IO.unit.bind()
    else GithubCommon.raiseError("Error creating the comment").bind()

    OutputFile.sendOutputToFile(outputToFile, outputPath, result, outputFormat).bind()

    val errors: List<BenchmarkComparison> = getWrongResults(result)

    if (errors.nonEmpty()) {
      IO.raiseError<Unit>(GradleException(errors.prettyPrintResult())).bind()
    } else
      GithubCommentIntegration.setSuccessStatus(
        info,
        commitSha
      ).bind()

  }.fix().handleErrorWith {
    GithubCommentIntegration.setFailedStatus(
      info,
      commitSha
    )
  }

  @TaskAction
  fun compareBenchmarkCI(): Unit =
    IO { System.getenv("TRAVIS_PULL_REQUEST") }.flatMap {

      if (!it.contains("false")) {
        IO.monad().binding {

          val slug = IO { System.getenv("TRAVIS_REPO_SLUG").split('/') }.bind()
          if (slug.size < 2)
            IO.raiseError<Unit>(GradleException("Error reading env var: TRAVIS_REPO_SLUG")).bind()
          val owner: String = slug.first()
          val repo: String = slug.last()

          val token: String =
            token.toOption()
              .fold({ IO.raiseError<String>(GradleException("Error getting Github token")) }) { IO { it } }
              .bind()

          val info = GhInfo(owner, repo, token)
          val commitSha: String = IO { System.getenv("TRAVIS_PULL_REQUEST_SHA") }.bind()

          compareCI(info, commitSha, it.toInt()).bind()
        }.fix()
      } else IO.unit

    }.unsafeRunSync()

}
