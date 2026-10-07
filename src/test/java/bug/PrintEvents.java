package bug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Prints the execution events (registered via META-INF/services) as one block when the test plan has finished, and
 * marks where a parent and its children overlap. Events after the end of the test plan are printed immediately.
 */
public class PrintEvents implements TestExecutionListener {

	private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

	private final Map<String, TestIdentifier> unfinished = new ConcurrentHashMap<>();

	private final Set<String> finished = ConcurrentHashMap.newKeySet();

	private volatile boolean planFinished;

	@Override
	public synchronized void dynamicTestRegistered(TestIdentifier identifier) {
		this.unfinished.put(identifier.getUniqueId(), identifier);
		add("REGISTERED " + identifier.getDisplayName() + parentFinished(identifier));
	}

	@Override
	public synchronized void executionStarted(TestIdentifier identifier) {
		this.unfinished.put(identifier.getUniqueId(), identifier);
		add("STARTED    " + identifier.getDisplayName() + parentFinished(identifier));
	}

	@Override
	public synchronized void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
		this.unfinished.remove(identifier.getUniqueId());
		this.finished.add(identifier.getUniqueId());
		String children = this.unfinished.values().stream() //
				.filter(child -> child.getParentId().filter(identifier.getUniqueId()::equals).isPresent()) //
				.map(TestIdentifier::getDisplayName).toList().toString();
		add("FINISHED   " + identifier.getDisplayName() + " " + result.getStatus()
				+ result.getThrowable().map(t -> " (" + t.getMessage() + ")").orElse("") + parentFinished(identifier)
				+ (children.equals("[]") ? "" : "   <-- its child " + children + " has not finished yet"));
	}

	@Override
	public synchronized void testPlanExecutionFinished(TestPlan testPlan) {
		this.planFinished = true;
		this.unfinished.values().forEach(
			node -> this.lines.add("no FINISHED event for " + node.getDisplayName() + " so far"));
		this.lines.add("TEST PLAN EXECUTION FINISHED");
		System.out.println(String.join(System.lineSeparator(), this.lines));
	}

	private String parentFinished(TestIdentifier identifier) {
		return identifier.getParentId().filter(this.finished::contains).map(
			parent -> "   <-- its parent has already FINISHED").orElse("");
	}

	private void add(String line) {
		if (this.planFinished) {
			System.out.println("after the test plan finished: " + line);
		}
		else {
			this.lines.add(line);
		}
	}

}
