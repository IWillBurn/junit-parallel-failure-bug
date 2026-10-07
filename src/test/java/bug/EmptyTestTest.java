package bug;

import java.util.stream.Stream;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No state at all: the test only takes half a second. Run this class on its own.
 */
@DisplayName("no state: @MethodSource gives \"a1\", \"a2\", then throws on \"b1\"")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class EmptyTestTest {

	@Nested
	@Order(1)
	@DisplayName("sequential (SAME_THREAD)")
	@Execution(ExecutionMode.SAME_THREAD)
	class Sequential extends Scenario {
	}

	@Nested
	@Order(2)
	@DisplayName("parallel (CONCURRENT)")
	@Execution(ExecutionMode.CONCURRENT)
	class Parallel extends Scenario {
	}

	abstract static class Scenario {

		@ParameterizedTest(name = "argument {0}")
		@MethodSource("arguments")
		void test(String argument) throws InterruptedException {
			Thread.sleep(500);
		}

		static Stream<String> arguments() {
			return Stream.of("a1", "a2", "b1", "b2", "c1", "c2").peek(argument -> {
				if (argument.equals("b1") || argument.equals("b2")) {
					throw new IllegalStateException("cannot provide b");
				}
			});
		}

	}

}
