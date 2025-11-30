# Contributing to GPU Support Framework

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing to the GPU Support Framework.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Testing Guidelines](#testing-guidelines)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)
- [Documentation](#documentation)

## Code of Conduct

This project adheres to professional standards of conduct. Please:

- Be respectful and inclusive
- Focus on constructive feedback
- Assume good intentions
- Keep discussions technical and professional

## Getting Started

### Prerequisites

- **Java 25+** (GraalVM 25.0.1+ recommended)
- **Maven 3.9+**
- **Git**
- **GPU Drivers** (optional - tests work without GPU)

### Fork and Clone

```bash
# Fork the repository on GitHub
# Then clone your fork
git clone https://github.com/YOUR-USERNAME/gpu-support.git
cd gpu-support

# Add upstream remote
git remote add upstream https://github.com/Hellblazer/gpu-support.git
```

## Development Setup

### Build the Project

```bash
# Full build with tests
mvn clean install

# Skip tests if needed
mvn clean install -DskipTests

# Run platform validation
mvn test -Dtest=HeadlessPlatformValidationTest
```

### IDE Setup

**IntelliJ IDEA:**
1. Import as Maven project
2. Enable annotation processing
3. Set Java language level to 25
4. Install Lombok plugin (if used)

**Eclipse:**
1. Import as Maven project
2. Install M2Eclipse plugin
3. Set compiler compliance to 25

**VS Code:**
1. Install Java Extension Pack
2. Install Maven extension
3. Open folder in VS Code

## Making Changes

### Branch Strategy

```bash
# Create a feature branch
git checkout -b feature/your-feature-name

# Or a bugfix branch
git checkout -b fix/issue-description
```

### Branch Naming

- `feature/` - New features
- `fix/` - Bug fixes
- `docs/` - Documentation changes
- `refactor/` - Code refactoring
- `test/` - Test improvements
- `perf/` - Performance improvements

### Commit Messages

Follow conventional commits format:

```
type(scope): subject

body (optional)

footer (optional)
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `test`: Adding/updating tests
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `perf`: Performance improvement
- `chore`: Maintenance tasks

**Examples:**
```
feat(resource): add Vulkan buffer support

Implements VulkanBufferHandle for Vulkan API resources.
Includes automatic cleanup and leak detection.

Closes #123

fix(test): skip GPU tests gracefully in CI

Updates CICompatibleGPUTest to properly detect CI environment
and use mock platform when GPU is unavailable.

docs: update README with installation instructions
```

## Testing Guidelines

### Test Requirements

**All contributions must include tests:**

1. **Unit tests** for new functionality
2. **Integration tests** for cross-module features
3. **CI compatibility** - tests must skip gracefully without GPU

### Writing Tests

**Resource Module Tests:**
```java
public class MyResourceTest {

    @Test
    void testResourceLifecycle() {
        var config = ResourceConfiguration.builder().build();
        var manager = new UnifiedResourceManager(config);

        try (var resource = manager.createResource()) {
            // Test code
            assertNotNull(resource);
        } // Automatic cleanup

        // Verify cleanup
        assertEquals(0, manager.getActiveResources());
    }
}
```

**GPU Test Framework Tests:**
```java
public class MyGPUTest extends CICompatibleGPUTest {

    @Test
    void testGPUFeature() {
        var platforms = discoverPlatforms();
        // Automatically skips when OpenCL unavailable

        var platform = platforms.get(0);
        if (MockPlatform.isMockPlatform(platform)) {
            log.info("Mock platform - skipping GPU test");
            return;
        }

        // GPU test code
    }
}
```

### Running Tests

```bash
# All tests
mvn test

# Specific module
mvn test -pl resource
mvn test -pl gpu-test-framework

# Specific test class
mvn test -Dtest=MyTest

# With coverage
mvn clean test jacoco:report
```

### Test Coverage

- Aim for **>80% code coverage**
- All public APIs must be tested
- Critical paths require 100% coverage
- Use JaCoCo for coverage reports

## Pull Request Process

### Before Submitting

1. **Update from upstream:**
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. **Run all tests:**
   ```bash
   mvn clean install
   ```

3. **Check code style:**
   ```bash
   mvn checkstyle:check
   ```

4. **Update documentation** if needed

### Creating a Pull Request

1. **Push to your fork:**
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Open PR on GitHub:**
   - Clear title describing the change
   - Reference related issues
   - Describe what changed and why
   - Include test results

3. **PR Template:**
   ```markdown
   ## Description
   Brief description of changes

   ## Type of Change
   - [ ] Bug fix
   - [ ] New feature
   - [ ] Breaking change
   - [ ] Documentation update

   ## Testing
   - [ ] All tests pass
   - [ ] New tests added
   - [ ] Manual testing performed

   ## Checklist
   - [ ] Code follows style guidelines
   - [ ] Documentation updated
   - [ ] No breaking changes (or documented)
   - [ ] CI build passes
   ```

### Review Process

- Maintainer will review within 7 days
- Address feedback promptly
- Keep discussions focused and technical
- Be open to suggestions

### Merging

- PRs require at least one approval
- CI must pass
- No merge conflicts
- All discussions resolved

## Coding Standards

### Java Style

Follow these conventions:

**Modern Java Features:**
```java
// Use var where type is obvious
var list = new ArrayList<String>();
var manager = new UnifiedResourceManager();

// Use text blocks for multi-line strings
String kernel = """
    __kernel void vectorAdd(__global float* a) {
        int gid = get_global_id(0);
        a[gid] = a[gid] * 2.0f;
    }
    """;

// Use switch expressions
var result = switch (type) {
    case BUFFER -> handleBuffer();
    case TEXTURE -> handleTexture();
    default -> throw new IllegalArgumentException();
};
```

**RAII Pattern:**
```java
// Always use try-with-resources
try (var resource = createResource()) {
    // Use resource
} // Automatic cleanup

// Implement AutoCloseable for resources
public class MyResource implements AutoCloseable {
    @Override
    public void close() {
        cleanup();
    }
}
```

**Thread Safety:**
```java
// Use modern concurrency utilities
ConcurrentHashMap<String, Resource> resources;
AtomicReference<State> state;
ReentrantReadWriteLock lock;

// Avoid synchronized blocks unless necessary
// Prefer lock-free data structures
```

### Naming Conventions

- **Classes:** `PascalCase`
- **Methods:** `camelCase`
- **Constants:** `UPPER_SNAKE_CASE`
- **Packages:** `lowercase`
- **Test classes:** `*Test` suffix

### Documentation

**JavaDoc for public APIs:**
```java
/**
 * Allocates memory from the pool.
 *
 * @param sizeBytes the size in bytes to allocate
 * @return a ByteBuffer from the pool or newly allocated
 * @throws IllegalArgumentException if size is negative
 */
public ByteBuffer allocateMemory(int sizeBytes) {
    // Implementation
}
```

**Inline comments for complex logic:**
```java
// Use identity map because ByteBuffer.equals() compares content,
// but we need to track specific instances
IdentityHashMap<ByteBuffer, BufferInfo> bufferTracking;
```

## Documentation

### What to Document

1. **Public APIs** - Complete JavaDoc
2. **Architecture decisions** - Why, not just what
3. **Usage examples** - Real-world scenarios
4. **Limitations** - Known issues and constraints
5. **Performance characteristics** - Benchmarks and trade-offs

### Documentation Locations

- **JavaDoc** - In source code
- **README.md** - Module overview and quick start
- **USAGE_GUIDE.md** - Detailed usage examples
- **docs/** - Architecture, guides, tutorials

### Markdown Style

```markdown
# Use ATX-style headers

## Not Setext style
Not this

Use **bold** for emphasis, not _italics_ primarily.

Use `code` for inline code, ``` for blocks.

[Links](URL) should be descriptive.

- Unordered lists with -
1. Ordered lists with numbers
```

## Performance Considerations

### Benchmarking

For performance-critical changes:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MyBenchmark {

    @Benchmark
    public void benchmarkOperation() {
        // Code to benchmark
    }
}
```

Run benchmarks:
```bash
mvn test -Pbenchmark
```

### Performance Requirements

- Pool operations: <1μs average
- Resource cleanup: <10μs
- Thread-safe operations: minimal contention
- Memory overhead: <5% for tracking

## Release Process

Maintainers handle releases, but contributors should:

1. Follow semantic versioning
2. Update CHANGELOG.md
3. Tag breaking changes clearly
4. Update version in pom.xml

## Getting Help

- **Questions**: Open a GitHub Discussion
- **Bugs**: Open an issue with reproducible example
- **Features**: Open an issue to discuss before implementing
- **Chat**: (Coming soon)

## Recognition

Contributors are recognized in:
- CHANGELOG.md
- Release notes
- README.md acknowledgments

Thank you for contributing to GPU Support Framework!
