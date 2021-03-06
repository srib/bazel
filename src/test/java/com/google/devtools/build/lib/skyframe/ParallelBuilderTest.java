// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Runnables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.LocalHostCapacity;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.util.TestAction;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.testutil.BlazeTestUtils;
import com.google.devtools.build.lib.testutil.Suite;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Test suite for ParallelBuilder.
 *
 */
@TestSpec(size = Suite.MEDIUM_TESTS)
@RunWith(JUnit4.class)
public class ParallelBuilderTest extends TimestampBuilderTestCase {

  private static final Logger LOG =
    Logger.getLogger(ParallelBuilderTest.class.getName());

  protected ActionCache cache;

  protected static final int DEFAULT_NUM_JOBS = 100;

  @Before
  public final void setUp() throws Exception {
    this.cache = new InMemoryActionCache();
    ResourceManager.instance().setAvailableResources(LocalHostCapacity.getLocalHostCapacity());
    ResourceManager.instance().setRamUtilizationPercentage(
        ResourceManager.DEFAULT_RAM_UTILIZATION_PERCENTAGE);
    ResourceManager.instance().resetResourceUsage();
  }

  @SafeVarargs
  protected static <T> Set<T> asSet(T... elements) {
    return Sets.newHashSet(elements);
  }

  protected void buildArtifacts(Artifact... artifacts) throws Exception {
    buildArtifacts(createBuilder(DEFAULT_NUM_JOBS, false), artifacts);
  }

  private Builder createBuilder(int jobs, boolean keepGoing) throws Exception {
    return createBuilder(cache, jobs, keepGoing);
  }

  private volatile boolean runningFooAction;
  private volatile boolean runningBarAction;

  /**
   * Test that independent actions are run in parallel threads
   * that are scheduled concurrently.
   */
  public void runsInParallelWithBuilder(Builder builder) throws Exception {
    // We create two actions, each of which waits (spinning) until the
    // other action has started.  If the two actions are not run
    // in parallel, the test will deadlock and time out.

    // This specifies how many iterations to run before timing out.
    // This should be large enough to ensure that that there is at
    // least one context switch, otherwise the test may spuriously fail.
    final long maxIterations = 100000000;

    // This specifies how often to print out progress messages.
    // Uncomment this for debugging.
    //final long PRINT_FREQUENCY = maxIterations / 10;

    runningFooAction = false;
    runningBarAction = false;

    // [action] -> foo
    Artifact foo = createDerivedArtifact("foo");
    Runnable makeFoo = new Runnable() {
          @Override
          public void run() {
            runningFooAction = true;
            for (long i = 0; i < maxIterations; i++) {
              Thread.yield();
              if (runningBarAction) {
                return;
              }
              // Uncomment this for debugging.
              //if (i % PRINT_FREQUENCY == 0) {
              //  String msg = "ParallelBuilderTest: foo: waiting for bar";
              //  System.out.println(bar);
              //}
            }
            fail("ParallelBuilderTest: foo: waiting for bar: timed out");
          }
        };
    registerAction(new TestAction(makeFoo, Artifact.NO_ARTIFACTS, ImmutableList.of(foo)));

    // [action] -> bar
    Artifact bar = createDerivedArtifact("bar");
    Runnable makeBar = new Runnable() {
          @Override
          public void run() {
            runningBarAction = true;
            for (long i = 0; i < maxIterations; i++) {
              Thread.yield();
              if (runningFooAction) {
                return;
              }
              // Uncomment this for debugging.
              //if (i % PRINT_FREQUENCY == 0) {
              //  String msg = "ParallelBuilderTest: bar: waiting for foo";
              //  System.out.println(msg);
              //}
            }
            fail("ParallelBuilderTest: bar: waiting for foo: timed out");
          }
        };
    registerAction(new TestAction(makeBar, Artifact.NO_ARTIFACTS, ImmutableList.of(bar)));

    buildArtifacts(builder, foo, bar);
  }

  /**
   * Intercepts actionExecuted events, ordinarily written to the master log, for
   * use locally within this test suite.
   */
  public static class ActionEventRecorder {
    private final List<ActionExecutedEvent> actionExecutedEvents = new ArrayList<>();

    @Subscribe
    public void actionExecuted(ActionExecutedEvent event) {
      actionExecutedEvents.add(event);
    }
  }

  @Test
  public void testReportsActionExecutedEvent() throws Exception {
    Artifact pear = createDerivedArtifact("pear");
    ActionEventRecorder recorder = new ActionEventRecorder();
    EventBus eventBus = new EventBus();
    eventBusRef.set(eventBus);
    eventBus.register(recorder);

    Action action = registerAction(new TestAction(Runnables.doNothing(), emptySet, asSet(pear)));

    buildArtifacts(createBuilder(DEFAULT_NUM_JOBS, true), pear);
    assertThat(recorder.actionExecutedEvents).hasSize(1);
    assertEquals(action, recorder.actionExecutedEvents.get(0).getAction());
  }

  @Test
  public void testRunsInParallel() throws Exception {
    runsInParallelWithBuilder(createBuilder(DEFAULT_NUM_JOBS, false));
  }

  /**
   * Test that we can recover properly after a failed build.
   */
  @Test
  public void testFailureRecovery() throws Exception {

    // [action] -> foo
    Artifact foo = createDerivedArtifact("foo");
    Callable<Void> makeFoo = new Callable<Void>() {
          @Override
          public Void call() throws IOException {
            throw new IOException("building 'foo' is supposed to fail");
          }
        };
    registerAction(new TestAction(makeFoo, Artifact.NO_ARTIFACTS, ImmutableList.of(foo)));

    // [action] -> bar
    Artifact bar = createDerivedArtifact("bar");
    registerAction(new TestAction(TestAction.NO_EFFECT, emptySet, ImmutableList.of(bar)));

    // Don't fail fast when we encounter the error
    reporter.removeHandler(failFastHandler);

    // test that building 'foo' fails
    try {
      buildArtifacts(foo);
      fail("building 'foo' was supposed to fail!");
    } catch (BuildFailedException e) {
      if (!e.getMessage().contains("building 'foo' is supposed to fail")) {
        throw e;
      }
      // Make sure the reporter reported the error message.
      assertContainsEvent("building 'foo' is supposed to fail");
    }
    // test that a subsequent build of 'bar' succeeds
    buildArtifacts(bar);
  }

  @Test
  public void testUpdateCacheError() throws Exception {
    FileSystem fs = new InMemoryFileSystem() {
      @Override
      public FileStatus stat(Path path, boolean followSymlinks) throws IOException {
        final FileStatus stat = super.stat(path, followSymlinks);
        if (path.toString().endsWith("/out/foo")) {
          return new FileStatus() {
            private final FileStatus original = stat;

            @Override
            public boolean isSymbolicLink() {
              return original.isSymbolicLink();
            }

            @Override
            public boolean isFile() {
              return original.isFile();
            }

            @Override
            public boolean isDirectory() {
              return original.isDirectory();
            }

            @Override
            public boolean isSpecialFile() {
              return original.isSpecialFile();
            }

            @Override
            public long getSize() throws IOException {
              return original.getSize();
            }

            @Override
            public long getNodeId() throws IOException {
              return original.getNodeId();
            }

            @Override
            public long getLastModifiedTime() throws IOException {
              throw new IOException();
            }

            @Override
            public long getLastChangeTime() throws IOException {
              return original.getLastChangeTime();
            }
          };
        }
        return stat;
      }
    };
    Artifact foo = createDerivedArtifact(fs, "foo");
    registerAction(new TestAction(TestAction.NO_EFFECT, emptySet, ImmutableList.of(foo)));
    reporter.removeHandler(failFastHandler);
    try {
      buildArtifacts(foo);
      fail("Expected to fail");
    } catch (BuildFailedException e) {
      assertContainsEvent("not all outputs were created");
    }
  }

  @Test
  public void testNullBuild() throws Exception {
    // BuildTool.setupLogging(Level.FINEST);
    LOG.fine("Testing null build...");
    buildArtifacts();
  }

  /**
   * Test a randomly-generated complex dependency graph.
   */
  @Test
  public void testSmallRandomStressTest() throws Exception {
    final int numTrials = 1;
    final int numArtifacts = 30;
    final int randomSeed = 42;
    StressTest test = new StressTest(numArtifacts, numTrials, randomSeed);
    test.runStressTest();
  }

  private static enum BuildKind { Clean, Incremental, Nop }

  /**
   * Sets up and manages stress tests of arbitrary size.
   */
  protected class StressTest {

    final int numArtifacts;
    final int numTrials;

    Random random;
    Artifact artifacts[];

    public StressTest(int numArtifacts, int numTrials, int randomSeed) {
      this.numTrials = numTrials;
      this.numArtifacts = numArtifacts;
      this.random = new Random(randomSeed);
    }

    public void runStressTest() throws Exception {
      for (int trial = 0; trial < numTrials; trial++) {
        List<Counter> counters = buildRandomActionGraph(trial);

        // do a clean build
        LOG.fine("Testing clean build... (trial " + trial + ")");
        Artifact[] buildTargets = chooseRandomBuild();
        buildArtifacts(buildTargets);
        doSanityChecks(buildTargets, counters, BuildKind.Clean);
        resetCounters(counters);

        // Do an incremental build.
        //
        // BuildTool creates new instances of the Builder for each build request. It may rely on
        // that fact (that its state will be discarded after each build request) - thus
        // test should use same approach and ensure that a new instance is used each time.
        LOG.fine("Testing incremental build...");
        buildTargets = chooseRandomBuild();
        buildArtifacts(buildTargets);
        doSanityChecks(buildTargets, counters, BuildKind.Incremental);
        resetCounters(counters);

        // do a do-nothing build
        LOG.fine("Testing do-nothing rebuild...");
        buildArtifacts(buildTargets);
        doSanityChecks(buildTargets, counters, BuildKind.Nop);
        //resetCounters(counters);
      }
    }

    /**
     * Construct a random action graph, and initialize the file system
     * so that all of the input files exist and none of the output files
     * exist.
     */
    public List<Counter> buildRandomActionGraph(int actionGraphNumber) throws IOException {
      List<Counter> counters = new ArrayList<>(numArtifacts);

      artifacts = new Artifact[numArtifacts];
      for (int i = 0; i < numArtifacts; i++) {
        artifacts[i] = createDerivedArtifact("file" + actionGraphNumber + "-" + i);
      }

      int numOutputs;
      for (int i = 0; i < artifacts.length; i += numOutputs) {
        int numInputs = random.nextInt(3);
        numOutputs = 1 + random.nextInt(2);
        if (i + numOutputs >= artifacts.length) {
          numOutputs = artifacts.length - i;
        }

        Collection<Artifact> inputs = new ArrayList<>(numInputs);
        for (int j = 0; j < numInputs; j++) {
          if (i != 0) {
            int inputNum = random.nextInt(i);
            inputs.add(artifacts[inputNum]);
          }
        }
        Collection<Artifact> outputs = new ArrayList<>(numOutputs);
        for (int j = 0; j < numOutputs; j++) {
          outputs.add(artifacts[i + j]);
        }
        counters.add(createActionCounter(inputs, outputs));
        if (inputs.isEmpty()) {
          // source files -- create them
          for (Artifact output : outputs) {
            BlazeTestUtils.makeEmptyFile(output.getPath());
          }
        } else {
          // generated files -- delete them
          for (Artifact output : outputs) {
            try {
              output.getPath().delete();
            } catch (FileNotFoundException e) {
              // ok
            }
          }
        }
      }
      return counters;
    }

    /**
     * Choose a random set of targets to build.
     */
    public Artifact[] chooseRandomBuild() {
      Artifact[] buildTargets;
      switch (random.nextInt(4)) {
        case 0:
          // build the final output target
          LOG.fine("Building final output target.");
          buildTargets = new Artifact[] { artifacts[numArtifacts - 1] };
          break;

        case 1: {
          // build all the targets (in random order);
          LOG.fine("Building all the targets.");
          List<Artifact> targets = Lists.newArrayList(artifacts);
          Collections.shuffle(targets, random);
          buildTargets = targets.toArray(new Artifact[numArtifacts]);
          break;
        }

        case 2:
          // build a random target
          LOG.fine("Building a random target.");
          buildTargets = new Artifact[] {
                artifacts[random.nextInt(numArtifacts)]
              };
          break;

        case 3: {
          // build a random subset of targets
          LOG.fine("Building a random subset of targets.");
          List<Artifact> targets = Lists.newArrayList(artifacts);
          Collections.shuffle(targets, random);
          List<Artifact> targetSubset = new ArrayList<>();
          int numTargetsToTest = random.nextInt(numArtifacts);
          LOG.fine("numTargetsToTest = " + numTargetsToTest);
          Iterator<Artifact> iterator = targets.iterator();
          for (int i = 0; i < numTargetsToTest; i++) {
            targetSubset.add(iterator.next());
          }
          buildTargets = targetSubset.toArray(new Artifact[numTargetsToTest]);
          break;
        }

        default:
          throw new IllegalStateException();
      }
      return buildTargets;
    }

    public void doSanityChecks(Artifact[] targets, List<Counter> counters,
        BuildKind kind) {
      // Check that we really did build all the targets.
      for (Artifact file : targets) {
        assertTrue(file.getPath().exists());
      }
      // Check that each action was executed the right number of times
      for (Counter counter : counters) {
        switch (kind) {
          case Clean:
            //assert counter.count == 1;
            //break;
          case Incremental:
            assert counter.count == 0 || counter.count == 1;
            break;
          case Nop:
            assert counter.count == 0;
            break;
        }
      }
    }

    private void resetCounters(List<Counter> counters) {
      for (Counter counter : counters) {
        counter.count = 0;
      }
    }

  }

  // Regression test for bug fixed in CL 3548332: builder was not waiting for
  // all its subprocesses to terminate.
  @Test
  public void testWaitsForSubprocesses() throws Exception {
    final Semaphore semaphore = new Semaphore(1);
    final boolean[] finished = { false };

    semaphore.acquireUninterruptibly(); // t=0: semaphore acquired

    // This arrangement ensures that the "bar" action tries to run for about
    // 100ms after the "foo" action has completed (failed).

    // [action] -> foo
    Artifact foo = createDerivedArtifact("foo");
    Callable<Void> makeFoo = new Callable<Void>() {
          @Override
          public Void call() throws IOException {
            semaphore.acquireUninterruptibly(); // t=2: semaphore re-acquired
            throw new IOException("foo action failed");
          }
        };
    registerAction(new TestAction(makeFoo, Artifact.NO_ARTIFACTS, ImmutableList.of(foo)));

    // [action] -> bar
    Artifact bar = createDerivedArtifact("bar");
    Runnable makeBar = new Runnable() {
          @Override
          public void run() {
            semaphore.release(); // t=1: semaphore released
            try {
              Thread.sleep(100); // 100ms
            } catch (InterruptedException e) {
              // This might happen (though not necessarily).  The
              // ParallelBuilder interrupts all its workers at the first sign
              // of trouble.
            }
            finished[0] = true;
          }
        };
    registerAction(new TestAction(makeBar, emptySet, asSet(bar)));

    // Don't fail fast when we encounter the error
    reporter.removeHandler(failFastHandler);

    try {
      buildArtifacts(foo, bar);
      fail();
    } catch (BuildFailedException e) {
      assertThat(e.getMessage()).contains("TestAction failed due to exception: foo action failed");
      assertContainsEvent("TestAction failed due to exception: foo action failed");
    }

    assertTrue("bar action not finished, yet buildArtifacts has completed.",
               finished[0]);
  }

  @Test
  public void testSchedulingOfMemoryResources() throws Exception {
    // The action graph consists of 100 independent actions, but execution is
    // memory limited: only 6 TestActions can run concurrently:
    ResourceManager.instance().setRamUtilizationPercentage(50);
    ResourceManager.instance().setAvailableResources(
        ResourceSet.createWithRamCpuIo(/*memoryMb=*/12.8, /*cpu=*/Integer.MAX_VALUE, /*io=*/0.0));
    ResourceManager.instance().resetResourceUsage();

    class Counter {
      int currentlyRunning = 0;
      int maxConcurrent = 0;
      synchronized void increment() {
        ++currentlyRunning;
        if (currentlyRunning > maxConcurrent) {
          maxConcurrent = currentlyRunning;
        }
      }
      synchronized void decrement() {
        currentlyRunning--;
      }
    }
    final Counter counter = new Counter();

    Artifact[] outputs = new Artifact[100];
    for (int ii = 0; ii < outputs.length; ++ii) {
      Artifact artifact = createDerivedArtifact("file" + ii);
      Callable<Void> callable = new Callable<Void>() {
          @Override
          public Void call() throws Exception{
            counter.increment();
            Thread.sleep(100); // 100ms
            counter.decrement();
            return null;
          }
        };
      registerAction(new TestAction(callable, Artifact.NO_ARTIFACTS, ImmutableList.of(artifact)));
      outputs[ii] = artifact;
    }

    buildArtifacts(outputs);

    assertEquals(0, counter.currentlyRunning);
    assertEquals(6, counter.maxConcurrent);
  }

  @Test
  public void testEstimateExceedsAvailableRam() throws Exception {
    // Pretend that the machine has only 1MB of RAM available,
    // then test running an action that we estimate requires 2MB of RAM.

    ResourceManager.instance().setAvailableResources(
        ResourceSet.createWithRamCpuIo(/*memoryMb=*/1.0, /*cpuUsage=*/4, /*ioUsage=*/0));
    ResourceManager.instance().resetResourceUsage();

    final boolean[] finished = { false };
    Artifact foo = createDerivedArtifact("foo");
    Runnable makeFoo = new Runnable() {
        @Override
        public void run() {
          finished[0] = true;
        }
      };
    registerAction(new TestAction(makeFoo, emptySet, asSet(foo)) {
        @Override
        public ResourceSet estimateResourceConsumption(Executor executor) {
          return ResourceSet.createWithRamCpuIo(/*memoryMb=*/2.0, /*cpuUsage=*/1, /*ioUsage=*/0);
        }
      });
    buildArtifacts(foo);
    assertTrue(finished[0]);
  }

  @Test
  public void testCyclicActionGraph() throws Exception {
    // foo -> [action] -> bar
    // bar -> [action] -> baz
    // baz -> [action] -> foo
    Artifact foo = createDerivedArtifact("foo");
    Artifact bar = createDerivedArtifact("bar");
    Artifact baz = createDerivedArtifact("baz");
    try {
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(foo), asSet(bar)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(baz)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(baz), asSet(foo)));
      buildArtifacts(foo);
      fail("Builder failed to detect cyclic action graph");
    } catch (BuildFailedException e) {
      assertEquals(e.getMessage(), CYCLE_MSG);
    }
  }

  @Test
  public void testSelfCyclicActionGraph() throws Exception {
    // foo -> [action] -> foo
    Artifact foo = createDerivedArtifact("foo");
    try {
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(foo), asSet(foo)));
      buildArtifacts(foo);
      fail("Builder failed to detect cyclic action graph");
    } catch (BuildFailedException e) {
      assertEquals(e.getMessage(), CYCLE_MSG);
    }
  }

  @Test
  public void testCycleInActionGraphBelowTwoActions() throws Exception {
    // bar -> [action] -> foo1
    // bar -> [action] -> foo2
    // baz -> [action] -> bar
    // bar -> [action] -> baz
    Artifact foo1 = createDerivedArtifact("foo1");
    Artifact foo2 = createDerivedArtifact("foo2");
    Artifact bar = createDerivedArtifact("bar");
    Artifact baz = createDerivedArtifact("baz");
    try {
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(foo1)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(foo2)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(baz), asSet(bar)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(baz)));
      buildArtifacts(foo1, foo2);
      fail("Builder failed to detect cyclic action graph");
    } catch (BuildFailedException e) {
      assertEquals(e.getMessage(), CYCLE_MSG);
    }
  }


  @Test
  public void testCyclicActionGraphWithTail() throws Exception {
    // bar -> [action] -> foo
    // baz -> [action] -> bar
    // bat, foo -> [action] -> baz
    Artifact foo = createDerivedArtifact("foo");
    Artifact bar = createDerivedArtifact("bar");
    Artifact baz = createDerivedArtifact("baz");
    Artifact bat = createDerivedArtifact("bat");
    try {
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(foo)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(baz), asSet(bar)));
      registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bat, foo), asSet(baz)));
      registerAction(new TestAction(TestAction.NO_EFFECT, ImmutableSet.<Artifact>of(), asSet(bat)));
      buildArtifacts(foo);
      fail("Builder failed to detect cyclic action graph");
    } catch (BuildFailedException e) {
      assertEquals(e.getMessage(), CYCLE_MSG);
    }
  }

  @Test
  public void testDuplicatedInput() throws Exception {
    // <null> -> [action] -> foo
    // (foo, foo) -> [action] -> bar
    Artifact foo = createDerivedArtifact("foo");
    Artifact bar = createDerivedArtifact("bar");
    registerAction(
        new TestAction(TestAction.NO_EFFECT, ParallelBuilderTest.<Artifact>asSet(), asSet(foo)));
    registerAction(
        new TestAction(TestAction.NO_EFFECT, Lists.<Artifact>newArrayList(foo, foo), asSet(bar)));
    buildArtifacts(bar);
  }


  // Regression test for bug #735765, "ParallelBuilder still issues new jobs
  // after one has failed, without --keep-going."  The incorrect behaviour is
  // that, when the first job fails, while no new jobs are added to the queue
  // of runnable jobs, the queue may have lots of work in it, and the
  // ParallelBuilder always completes these jobs before it returns.  The
  // correct behaviour is to discard all the jobs in the queue after the first
  // one fails.
  public void assertNoNewJobsAreRunAfterFirstFailure(final boolean catastrophe, boolean keepGoing)
      throws Exception {
    // Strategy: Limit parallelism to 3.  Enqueue 10 runnable tasks that run
    // for an appreciable period (say 100ms).  Ensure that at most 3 of those
    // tasks completed.  This proves that all runnable tasks were dropped from
    // the queue after the first batch (which included errors) was finished.
    // It should be pretty robust even in the face of timing variations.

    final AtomicInteger completedTasks = new AtomicInteger(0);

    int numJobs = 50;
    Artifact[] artifacts = new Artifact[numJobs];

    for (int ii = 0; ii < numJobs; ++ii) {
      Artifact out = createDerivedArtifact(ii + ".out");
      List<Artifact> inputs = (catastrophe && ii > 10)
          ? ImmutableList.of(artifacts[0])
          : Artifact.NO_ARTIFACTS;
      final int iCopy = ii;
      registerAction(new TestAction(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            Thread.sleep(100); // 100ms
            completedTasks.getAndIncrement();
            throw new IOException("task failed");
          }
        },
          inputs, ImmutableList.of(out)) {
        @Override
        public void execute(ActionExecutionContext actionExecutionContext)
        throws ActionExecutionException {
          if (catastrophe && iCopy == 0) {
            try {
              Thread.sleep(300); // 300ms
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            completedTasks.getAndIncrement();
            throw new ActionExecutionException("This is a catastrophe", this, true);
          }
          super.execute(actionExecutionContext);
        }
      });
      artifacts[ii] = out;
    }

    // Don't fail fast when we encounter the error
    reporter.removeHandler(failFastHandler);

    try {
      buildArtifacts(createBuilder(3, keepGoing), artifacts);
      fail();
    } catch (BuildFailedException e) {
      assertContainsEvent("task failed");
    }
    if (completedTasks.get() >= numJobs) {
      fail("Expected early termination due to failed task, but all tasks ran to completion.");
    }
  }

   @Test
   public void testNoNewJobsAreRunAfterFirstFailure() throws Exception {
     assertNoNewJobsAreRunAfterFirstFailure(false, false);
   }

   @Test
   public void testNoNewJobsAreRunAfterCatastrophe() throws Exception {
     assertNoNewJobsAreRunAfterFirstFailure(true, true);
   }

  private Artifact createInputFile(String name) throws IOException {
    Artifact artifact = createSourceArtifact(name);
    Path path = artifact.getPath();
    FileSystemUtils.createDirectoryAndParents(path.getParentDirectory());
    FileSystemUtils.createEmptyFile(path);
    return artifact;
  }

  @Test
  public void testProgressReporting() throws Exception {
    // Build three artifacts in 3 separate actions (baz depends on bar and bar
    // depends on foo.  Make sure progress is reported at the beginning of all
    // three actions.
    List<Artifact> sourceFiles = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      sourceFiles.add(createInputFile("file" + i));
    }
    Artifact foo = createDerivedArtifact("foo");
    Artifact bar = createDerivedArtifact("bar");
    Artifact baz = createDerivedArtifact("baz");
    bar.getPath().delete();
    baz.getPath().delete();

    final List<String> messages = new ArrayList<>();
    EventHandler handler = new EventHandler() {

      @Override
      public void handle(Event event) {
        EventKind k = event.getKind();
        if (k == EventKind.START || k == EventKind.FINISH) {
          // Remove the tmpDir as this is user specific and the assert would
          // fail below.
          messages.add(
              event.getMessage().replaceFirst(TestUtils.tmpDir(), "") + " " + event.getKind());
        }
      }
    };
    reporter.addHandler(handler);
    reporter.addHandler(new PrintingEventHandler(EventKind.ALL_EVENTS));

    registerAction(new TestAction(TestAction.NO_EFFECT, sourceFiles, asSet(foo)));
    registerAction(new TestAction(TestAction.NO_EFFECT, asSet(foo), asSet(bar)));
    registerAction(new TestAction(TestAction.NO_EFFECT, asSet(bar), asSet(baz)));
    buildArtifacts(baz);
    // Check that the percentages increase non-linearly, because foo has 10 input files
    List<String> expectedMessages = Lists.newArrayList(
        "Test foo START",
        "Test foo FINISH",
        "Test bar START",
        "Test bar FINISH",
        "Test baz START",
        "Test baz FINISH");
    assertThat(messages).containsAllIn(expectedMessages);

    // Now do an incremental rebuild of bar and baz,
    // and check the incremental progress percentages.
    messages.clear();
    bar.getPath().delete();
    baz.getPath().delete();
    // This uses a new builder instance so that we refetch timestamps from
    // (in-memory) file system, rather than using cached entries.
    buildArtifacts(baz);
    expectedMessages = Lists.newArrayList(
        "Test bar START",
        "Test bar FINISH",
        "Test baz START",
        "Test baz FINISH");
    assertThat(messages).containsAllIn(expectedMessages);
  }
}
