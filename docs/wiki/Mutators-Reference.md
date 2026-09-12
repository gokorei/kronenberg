# Kronenberg AST Mutators Reference

This document is the code-backed catalog of all **27 AST mutation operators** implemented in Kronenberg.
Every mutator is implemented via pure K2 PSI AST visitors (`KtTreeVisitorVoid`), avoiding regular expressions or bytecode transformations.

**Total Mutators**: 27 | **Categories**: 19

---

### Arithmetic Operators

| Mutator Class | Description |
| :--- | :--- |
| `ArithmeticOperatorMutator` | Mutates binary arithmetic operators (+ <-> -, * <-> /, % <-> *) |

### Bitwise Operators

| Mutator Class | Description |
| :--- | :--- |
| `BitwiseOperatorMutator` | Mutates bitwise infix operators (and <-> or, xor <-> and) |

### Boolean Inversions

| Mutator Class | Description |
| :--- | :--- |
| `BooleanInversionMutator` | Inverts boolean operators (&& <-> \|\|, !flag <-> flag) |

### Collection Operators

| Mutator Class | Description |
| :--- | :--- |
| `CollectionOperatorMutator` | Inverts collection methods (filter <-> filterNot, any <-> all, map <-> mapNotNull, sorted <-> sortedDescending) |

### Compound Assignments

| Mutator Class | Description |
| :--- | :--- |
| `CompoundAssignmentMutator` | Mutates compound assignments (+= <-> -=, *= <-> /=, %= <-> *=) |

### Condition Replacements

| Mutator Class | Description |
| :--- | :--- |
| `ConditionReplacementMutator` | Replaces boolean if-conditions with constant true and false |

### Coroutine & Concurrency

| Mutator Class | Description |
| :--- | :--- |
| `CoroutineConcurrencyMutator` | Mutates Kotlin coroutine dispatchers, cancellation hierarchies, and builders |
| `CoroutineFlowMutator` | Mutates Coroutine and Flow operators (delay, flow filter/first/last) |

### Equality & Identity

| Mutator Class | Description |
| :--- | :--- |
| `EqualityMutator` | Mutates equality comparisons (== <-> !=, === <-> !==) |

### Extreme / Structural Mutations

| Mutator Class | Description |
| :--- | :--- |
| `DataClassCopyMutator` | Mutates data class copy() calls by stripping parameter overrides |
| `DestructuringMutator` | Mutates destructuring declarations by swapping positional variable bindings |
| `SmartCastMutator` | Mutates type checks and casts (is <-> !is, as <-> as?) |

### Literal Mutations

| Mutator Class | Description |
| :--- | :--- |
| `LiteralMutationMutator` | Mutates numeric constant literals (x -> x+1, x-1) |
| `StringTemplateMutator` | Mutates interpolated expressions within string templates |

### Null Safety & Elvis

| Mutator Class | Description |
| :--- | :--- |
| `ElvisLeftHandMutator` | Mutates elvis expressions (a ?: b -> left-hand a) |
| `NonNullAssertionMutator` | Mutates non-null assertions (s!! -> s) |
| `NullSafetyMutator` | Mutates elvis expressions (a ?: b -> default b) |
| `SafeCallMutator` | Mutates safe call operator (?. -> !!) |

### Precondition & Defensive Assertion

| Mutator Class | Description |
| :--- | :--- |
| `PreconditionMutator` | Mutates defensive precondition assertions (require, check, requireNotNull, checkNotNull) |

### Range Operators

| Mutator Class | Description |
| :--- | :--- |
| `RangeOperatorMutator` | Mutates range expressions (0 until n <-> 0..n, downTo <-> .., 0..<n <-> 0..n) |

### Relational Boundary

| Mutator Class | Description |
| :--- | :--- |
| `RelationalBoundaryMutator` | Mutates relational comparisons (< <-> <=, > <-> >=) |

### Result & Error Handling

| Mutator Class | Description |
| :--- | :--- |
| `ResultMutator` | Mutates Result and functional error handling calls (getOrElse, getOrDefault, getOrNull, onSuccess, onFailure) |

### Return Values

| Mutator Class | Description |
| :--- | :--- |
| `ReturnValueMutator` | Mutates return values (return true -> false, return x -> 0, return str -> "", emptyList, null) |

### Scope Function

| Mutator Class | Description |
| :--- | :--- |
| `ScopeFunctionMutator` | Mutates Kotlin standard library scope functions (apply <-> also, let <-> run) |
| `TakeIfMutator` | Inverts predicate filtering calls (takeIf <-> takeUnless) |

### Unary Operators

| Mutator Class | Description |
| :--- | :--- |
| `UnaryOperatorMutator` | Mutates unary operators (+x <-> -x, ++x <-> --x, x++ <-> x--) |

### Void Method Calls

| Mutator Class | Description |
| :--- | :--- |
| `VoidMethodCallMutator` | Omits side-effect method calls by replacing statement with Unit |
