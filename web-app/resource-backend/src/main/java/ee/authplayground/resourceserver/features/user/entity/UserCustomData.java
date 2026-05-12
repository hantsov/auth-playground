package ee.authplayground.resourceserver.features.user.entity;

/**
 * Typed representation of the {@code custom_data} JSONB column.
 * Stored as JSON in PostgreSQL; Hibernate maps it via {@code @JdbcTypeCode(SqlTypes.JSON)}.
 * Add or remove fields here as the data model evolves — a matching Flyway migration
 * is only needed when renaming/removing JSON keys that already exist in the database.
 */
public record UserCustomData(
        String department,
        String role,
        String team,
        String startDate
) {}
