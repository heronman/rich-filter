# Filter JSON DSL Specification v1.0

## 1. Overview

This document specifies a JSON-based filter DSL used to describe
database query predicates in a structured form.

The filter JSON is parsed into an abstract syntax tree consisting of:
- fields
- operators
- aggregators (`and` / `or`)
- flags (`CS`, `NF`)

The grammar is **structural** (JSON-based) and **context-sensitive**
for semantics (e.g. field names, flags inheritance).

---

## 2. Terminology

- **Flag**: Special key-value pair that controls behavior:
    - `CS` — case sensitivity for text comparison.
    - `NF` — null ordering for comparison operators.

- **Field name**: A key that corresponds to a DB column name.
- **Operator**: Binary operation on a field:
    - `eq`, `ne`, `gt`, `ge`, `lt`, `le`, `in`, `nin`, `like`.
- **Aggregator**: Logical combination of sub-expressions:
    - `"and"` or `"or"`.
- **Root**: The top-level JSON value (object or array).

All JSON objects must obey standard JSON rules:
- keys are unique within an object.

---

## 3. Flags

### 3.1 Syntax

A `<flag>` is defined as a single key-value pair in an object:

```json5
{ "<flag name>": <flag value> }
```
Supported flag names:
- CS - Case Sensitive
  - `<flag value>`: true | false
  - default: true
  - applies only when the field's DB type is textual; ignored otherwise.
- NF — Nulls First
  - `<flag value>`: true | false | null
  - default: null
  - only meaningful for comparison operators: gt, ge, lt, le; ignored for all other operators.

Flags can appear:
- at root object level,
- inside field descriptors,
- inside operator descriptors,
- as items of aggregator arrays (and / or),
- as items of the root array.

### 3.2 Semantics and Scope

Let a flag F be defined at some JSON node N.

- N may be:
  - a JSON object
  - or a JSON array (root-level or aggregator array).

Scope:
- The flag applies to:
  - the node N itself, and
  - all nested elements (recursively) inside N.
- If N is an aggregator array (`"and"`, `"or"`) or the root array, then the flag applies to all items in that array, regardless of their order.
- Flags can be overridden:
  - Any nested object may redefine the same flag name, which overrides the inherited value for that subtree.

Uniqueness:
- At a given hierarchy level (object or array), a flag with the same `<flag name>` must not be defined more than once.
  - In an object: this is guaranteed by JSON (keys must be unique).
  - In an array: there must be at most one array element that is a `<flag>` with a given `<flag name>`.

## 4. Field Name
`<field name>` is a DB field identifier.

Constraints:
- Must be a non-empty string.
- Must not be null.
- Must not be one of the reserved keys:
  - operator names: eq, ne, gt, ge, lt, le, in, nin, like
  - aggregators: and, or
  - flags: CS, NF.

The actual mapping from `<field name>` to DB columns is implementation-specific.

## 5. Value
`<value>` is the second argument to an operator.

### 5.1 For in / nin

- `<value>` MUST be an array:
  - not null
  - non-empty
  - each item is a plain, non-null JSON value:
    - number, string, boolean, or other scalar (no nested objects or arrays).

### 5.2 For all other operators
- `<value>` MUST be:
  - a plain JSON value (number, string, boolean, etc.) or null,
  - NOT an array.

The type compatibility of `<value>` with the DB field type is validated by the implementation (e.g. parser / type checker).

## 6. Operators
`<op name>` — one of:
- `"eq"` — equality
- `"ne"` — inequality
- `"gt"` — greater than
- `"ge"` — greater or equal
- `"lt"` — less than
- `"le"` — less or equal
- `"in"` — inclusion in list
- `"nin"` — exclusion from list
- `"like"`— pattern matching (string-like)
Semantics of operators with `null` (recommended):
- `eq(null)` → `IS NULL`
- `ne(null)` → `IS NOT NULL`
- `in`/`nin` — arrays must not contain `null`.

## 7. Field
A `<field>` is an object with a single DB field name as key:
```json5
{ "<field name>": <object> }
```
Where `<object>` is one of:
- `<agg>` — nested aggregator
- `<op descriptor>` — explicit operator descriptor
- `<op>` — embedded operator form
- `<value>` — shorthand, translated into eq or in depending on type:
  - plain value → eq
  - array value → in

The presence of a `<field>` sets the current field context for nested operators and values, until overridden by another field.

## 8. Operator Descriptor
An `<op descriptor>` is an object that explicitly describes an operation:
```json5
{
  "<optional flags>",
  "op": "<op name>",
  "field": "<field name>",   // optional, see context rules
  "value": <value>
}
```
Allowed keys:
- `<flag>` (0 or more, but at most one per flag name per level)
- `"op"`: `<op name>`
- `"field"`: `<field name>`
- `"value"`: `<value>`

Constraints:
- `"op"`:
  - mandatory if the descriptor itself is not already associated with an operator name above (i.e. not used as the value of `<op>` pair).
  - prohibited if the descriptor is directly used as `<op>`’s value (see section 9).
- `"field"`:
  - mandatory if no field name is defined up the hierarchy (no enclosing `<field>`).
  - prohibited if the field name is already defined above (inside a `<field>`).
- `"value"`:
  - mandatory

Unknown keys are prohibited.

## 9. Operator (`<op>`)

An `<op>` is an object with exactly one operator name as its key:

```json5
{ "<op name>": <object> }
```

Where `<object>` is either:
- an `<op descriptor>`, or
- a bare `<value>`.

### 9.1 `<op>` with `<op descriptor>`

Example:

```json5
{ "gt": { "value": 10 } }
```

Semantics: use operator `"gt"` and the descriptor object as-is, with implied `"op": "gt"`.

Constraints:
- The inner `<op descriptor>` MUST NOT explicitly contain `"op"` (since it is determined by the outer `<op name>`).
- All rules from section 8 apply.

### 9.2 `<op>` with bare `<value>`

Example:

```json5
{ "gt": 10 }
```

Constraints:
- Prohibited if the field name is not yet defined up the hierarchy.
- The pair is translated into an `<op descriptor>`:

```json5
{
    "op": "<op name>",
    "value": <value>,
    // field inherited from enclosing <field>, if any
    // flags inherited from current context
}
```

This is purely a syntactic shorthand.

## 10. Aggregator (`<agg>`)

An `<agg>` is an object with a single key `"and"` or `"or"`:

```json5
{ "and": <object> }
{ "or": <object> }
```

The value `<object>` can be:

### 10.1 Array form
```json5
{ "and": [ <item>, ... ] }
```

Array item types:
- `<value>` (only if a field name is already defined up the hierarchy)
  - translated into eq (for plain values) or in (for arrays)
- `<field>` (if the field name is not yet defined)
- `<op>`
- `<agg>`
- `<flag>`

If a `<flag>` appears as an array item:
- It defines or overrides the flag for:
  - all items of this array (both before and after the flag),
  - the nested content of each item.

Within a single aggregator array, at most one item may define a given flag name (e.g., only one `{ "NF": ... }` item is allowed in the array).

### 10.2 Object form
```json5
{
  "and": {
    "<field>": ...,
    "<op>": ...,
    "<agg>": ...,
    "<flag>": ...
  }
}
```

Item types:
- `<field>` (if the field name is not already defined in the current context)
- `<op>`
- `<agg>`
- `<flag>`

Unknown keys are prohibited.

Flags in the aggregator’s object value behave like normal flags: they apply to that object and its nested contents.

## 11. Root

`<root>` is the top-level JSON value:
- Can be an object or an array.

### 11.1 Root object

A root object is treated the same as a generic object described above and can contain:
- `<flag>`
- `<agg>`
- `<op>`
- `<field>`

Translation:
- If it contains multiple items, they are treated as an implicit `"and"` block combining all contained expressions (except flags, which only affect context).
- If it contains a single non-flag expression, it is treated as that expression.

### 11.2 Root array

A root array is treated as an implicit `"and"` aggregator:
```json5
[ <item>, ... ]
```

Equivalent to:
```json5
{ "and": [ <item>, ... ] }
```

Valid item types in a root array:
- `<flag>`
- `<agg>`
- `<op>`
- `<field>`

Flags in the root array behave as aggregator-array flags: they apply to all items in the array.

## 12. Invalid Constructs (Non-exhaustive)

The following constructs are explicitly invalid:
- Multiple definitions of the same flag name at the same hierarchy level (same object or same array).
- Using a bare `<value>` in an `<agg>` when no field name is defined up the hierarchy.
- Using an array `<value>` for operators other than in / nin.
- Inner `<op descriptor>` that explicitly contains `"op"` when used as `<op>` value.
- Unknown keys in any object that is interpreted as `<op descriptor>`, `<agg>` value, or `<field>` contents.
- Root object that contains only flags and no operators, fields, or aggregators.
