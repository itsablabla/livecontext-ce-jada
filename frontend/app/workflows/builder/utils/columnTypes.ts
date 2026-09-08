/**
 * Shared constants for column types and SQL operators
 * Used across CRUD operations (Create Column, Read/Update/Delete WHERE conditions)
 * Extracted from ParameterColumn.tsx to follow DRY principle
 */

/**
 * Available column types for Create Column operation
 * Defines the data types that can be assigned to new columns.
 *
 * Vector is listed unconditionally since 2026-09-03. It used to be spliced out on managed cloud,
 * because the server refused it there for every account. The server now refuses it only for an
 * account whose plan does not include it, which this module-scope array cannot know: it is
 * evaluated once, with no user in scope. Splicing it out would also hide it from the accounts
 * that pay for it, so the marker moved to the components that render this list, which can ask
 * `useVectorFeatureLock()` and draw a padlock naming the plan.
 */
export const COLUMN_TYPES = [
  { value: 'text', label: 'Text' },
  { value: 'number', label: 'Number' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'json', label: 'JSON' },
  { value: 'rating', label: 'Rating' },
  { value: 'sentiment', label: 'Sentiment' },
  { value: 'file', label: 'File' },
  { value: 'image', label: 'Image' },
  { value: 'select', label: 'Select' },
  { value: 'badge', label: 'Badge' },
  { value: 'progress', label: 'Progress' },
  { value: 'tags', label: 'Tags' },
  { value: 'code', label: 'Code' },
  { value: 'link', label: 'Link' },
  { value: 'vector', label: 'Vector' },
] as const;

/**
 * SQL comparison operators for WHERE conditions
 * Used in Read Row, Update Row, and Delete Row operations.
 * SIMILAR_TO drives vector similarity search, a plan capability rather than an edition one since
 * 2026-09-03 (see COLUMN_TYPES above for why the list itself no longer decides).
 *
 * Listing it unconditionally also repairs an existing inconsistency: an imported workflow plan
 * could already produce a SIMILAR_TO condition on cloud, where the operator was absent from this
 * list, leaving the operator select blank while the query-vector fields it drives were rendered.
 */
export const SQL_OPERATORS = [
  { value: '==', label: '== (equals)' },
  { value: '!=', label: '!= (not equals)' },
  { value: '>', label: '> (greater than)' },
  { value: '<', label: '< (less than)' },
  { value: '>=', label: '>= (greater or equal)' },
  { value: '<=', label: '<= (less or equal)' },
  { value: 'LIKE', label: 'LIKE (pattern match)' },
  { value: 'IN', label: 'IN (list)' },
  { value: 'SIMILAR_TO', label: 'SIMILAR TO (vector)' },
  { value: 'IS NULL', label: 'IS NULL' },
  { value: 'IS NOT NULL', label: 'IS NOT NULL' },
] as const;

/**
 * Operators that don't require a value input
 * Used to conditionally hide the value field in WHERE condition builders
 */
export const NULL_OPERATORS = ['IS NULL', 'IS NOT NULL'] as const;

/**
 * Operators that use similarity-specific fields (queryVector, topK) instead of a simple value
 */
export const SIMILARITY_OPERATORS = ['SIMILAR_TO'] as const;

/**
 * Type definitions for TypeScript
 */
export type ColumnType = typeof COLUMN_TYPES[number]['value'];
export type SqlOperator = typeof SQL_OPERATORS[number]['value'];
