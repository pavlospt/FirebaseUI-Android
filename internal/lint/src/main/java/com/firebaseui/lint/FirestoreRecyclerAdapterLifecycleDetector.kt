package com.firebaseui.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category.Companion.CORRECTNESS
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity.WARNING
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.visitor.AbstractUastVisitor
import java.util.EnumSet

class FirestoreRecyclerAdapterLifecycleDetector : Detector(), Detector.UastScanner {

  override fun getApplicableUastTypes() = listOf(UClass::class.java)

  override fun createUastHandler(context: JavaContext) = MissingLifecycleMethodsVisitor(context)

  class MissingLifecycleMethodsVisitor(
      private val context: JavaContext
  ) : UElementHandler() {
    private val FIRESTORE_RECYCLER_ADAPTER_TYPE =
        "FirestoreRecyclerAdapter"
    private val FIRESTORE_REYCLER_OPTIONS_BUILDER_TYPE =
        "FirestoreRecyclerOptions.Builder"

    override fun visitClass(node: UClass) {
      val adapterReferences = node
          .fields
          .filter { FIRESTORE_RECYCLER_ADAPTER_TYPE == it.type.canonicalText }
          .map { AdapterReference(it) }

      val recyclerOptionsReferences = node
          .fields
          .filter { FIRESTORE_REYCLER_OPTIONS_BUILDER_TYPE == it.type.canonicalText }
          .toMutableSet()

      node.accept(AdapterStartListeningMethodVisitor(adapterReferences))
      node.accept(AdapterStopListeningMethodVisitor(adapterReferences))
      node.accept(LifecycleOwnerMethodVisitor(recyclerOptionsReferences))

      adapterReferences.forEach {
        if (it.hasCalledStart && !it.hasCalledStop) {
          context.report(
              ISSUE_MISSING_LISTENING_STOP_METHOD,
              it.uField,
              context.getLocation(it.uField),
              "Have called .startListening() without .stopListening()."
          )
        } else if (!it.hasCalledStart) {
          context.report(
              ISSUE_MISSING_LISTENING_START_METHOD,
              it.uField,
              context.getLocation(it.uField),
              "Have not called .startListening()."
          )
        }
      }

      recyclerOptionsReferences.forEach {
        context.report(
            ISSUE_MISSING_LIFECYCLE_OWNER_METHODS,
            it,
            context.getLocation(it),
            "Have not called .setLifecycleOwner() on FirestoreRecyclerOptions."
        )
      }
    }
  }

  class AdapterStartListeningMethodVisitor(
      private val adapterReferences: List<AdapterReference>
  ) : AbstractUastVisitor() {
    private val START_LISTENING_METHOD_NAME = "startListening"

    override fun visitCallExpression(node: UCallExpression): Boolean =
        if (START_LISTENING_METHOD_NAME == node.methodName) {
          adapterReferences
              .find { it.uField.name == node.receiver?.asRenderString() }
              ?.let {
                it.hasCalledStart = true
              }
          true
        } else {
          super.visitCallExpression(node)
        }
  }

  class AdapterStopListeningMethodVisitor(
      private val adapterReferences: List<AdapterReference>
  ) : AbstractUastVisitor() {
    private val STOP_LISTENING_METHOD_NAME = "stopListening"

    override fun visitCallExpression(node: UCallExpression): Boolean =
        if (STOP_LISTENING_METHOD_NAME == node.methodName) {
          adapterReferences
              .find { it.uField.name == node.receiver?.asRenderString() }
              ?.let {
                it.hasCalledStop = true
              }
          true
        } else {
          super.visitCallExpression(node)
        }
  }

  class LifecycleOwnerMethodVisitor(
      private val recyclerOptionsReferences: MutableSet<UField>
  ) : AbstractUastVisitor() {

    private val SET_LIFECYCLE_OWNER_METHOD_NAME = "setLifecycleOwner"

    override fun visitCallExpression(node: UCallExpression): Boolean =
        if (SET_LIFECYCLE_OWNER_METHOD_NAME == node.methodName) {
          val iterator = recyclerOptionsReferences.iterator()
          while (iterator.hasNext()) {
            if (node.receiver?.asRenderString() == iterator.next().name) {
              iterator.remove()
            }
          }

          true
        } else {
          super.visitCallExpression(node)
        }
  }

  companion object {
    val ISSUE_MISSING_LIFECYCLE_OWNER_METHODS = Issue.create(
        "FirestoreRecyclerOptionsMissingLifecycleOwnerMethod",
        "Checks if FirestoreRecyclerOptions has called .setLifecycleOwner().",
        "If a class is using a FirestoreAdapter with FirestoreRecyclerOptions and " +
            "has not called .setLifecycleOwner() on FirestoreRecyclerOptions, it won't be " +
            "notified on changes.",
        CORRECTNESS, 10, WARNING,
        Implementation(
            FirestoreRecyclerAdapterLifecycleDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE)
        )
    )

    val ISSUE_MISSING_LISTENING_START_METHOD = Issue.create(
        "FirestoreAdapterMissingStartListeningMethod",
        "Checks if FirestoreAdapter has called .startListening() method.",
        "If a class is using a FirestoreAdapter and does not call startListening it won't be " +
            "notified on changes.",
        CORRECTNESS, 10, WARNING,
        Implementation(
            FirestoreRecyclerAdapterLifecycleDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE)
        )
    )

    val ISSUE_MISSING_LISTENING_STOP_METHOD = Issue.create(
        "FirestoreAdapterMissingStopListeningMethod",
        "Checks if FirestoreAdapter has called .stopListening() method.",
        "If a class is using a FirestoreAdapter and has called .startListening() but missing " +
            " .stopListening() might cause issues with RecyclerView data changes.",
        CORRECTNESS, 10, WARNING,
        Implementation(
            FirestoreRecyclerAdapterLifecycleDetector::class.java,
            EnumSet.of(Scope.JAVA_FILE)
        )
    )
  }
}

data class AdapterReference(
    val uField: UField,
    var hasCalledStart: Boolean = false,
    var hasCalledStop: Boolean = false
)
