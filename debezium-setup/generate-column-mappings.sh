#!/bin/bash

# Auto-generate PostgreSQL views with snake_case names for all tables
# This script creates views that map PascalCase columns to snake_case

DATABASE=${1:-"target_db"}
SCHEMA=${2:-"dbo"}
POSTGRES_USER=${3:-"admin"}

echo "=========================================================================="
echo "Generating snake_case views for all tables in $DATABASE.$SCHEMA"
echo "=========================================================================="
echo ""

# Function to convert PascalCase/camelCase to snake_case
to_snake_case() {
    # Handle sequences like "EmployeeID" -> "employee_id"
    # 1. Insert underscore before uppercase letters that follow lowercase
    # 2. Insert underscore before uppercase letters that are followed by lowercase (for acronyms)
    # 3. Convert to lowercase
    echo "$1" | sed -E 's/([a-z0-9])([A-Z])/\1_\2/g' | sed -E 's/([A-Z]+)([A-Z][a-z])/\1_\2/g' | tr '[:upper:]' '[:lower:]'
}

# Get all tables
tables=$(docker exec postgres18 psql -U $POSTGRES_USER -d $DATABASE -t -c "
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = '$SCHEMA' 
  AND table_type = 'BASE TABLE'
ORDER BY table_name;
" 2>/dev/null)

if [[ -z "$tables" ]]; then
    echo "❌ No tables found in schema $SCHEMA"
    echo "Make sure Debezium has completed the initial snapshot"
    exit 1
fi

view_count=0

# Process each table
while IFS= read -r table; do
    table=$(echo "$table" | xargs)  # Trim whitespace
    
    if [[ -z "$table" ]]; then
        continue
    fi
    
    view_name=$(to_snake_case "$table")
    
    # Skip if view name is same as table name (already snake_case)
    if [[ "$table" == "$view_name" ]]; then
        echo "⏭️  Skipping $table (already snake_case)"
        continue
    fi
    
    echo "📋 Processing: $SCHEMA.\"$table\" → $SCHEMA.$view_name"
    
    # Get all columns and convert to snake_case
    column_mappings=()
    while IFS= read -r col; do
        col=$(echo "$col" | xargs)
        if [[ -n "$col" ]]; then
            col_snake=$(to_snake_case "$col")
            if [[ "$col" == "$col_snake" ]]; then
                # Column already snake_case, no need to quote
                column_mappings+=("$col")
            else
                # Column needs transformation
                column_mappings+=("\"$col\" AS $col_snake")
            fi
        fi
    done < <(docker exec postgres18 psql -U $POSTGRES_USER -d $DATABASE -t -c "
    SELECT column_name 
    FROM information_schema.columns 
    WHERE table_schema = '$SCHEMA' 
      AND table_name = '$table'
    ORDER BY ordinal_position;
    " 2>/dev/null)
    
    # Join array elements with comma and newline
    column_list=$(printf "%s,\n" "${column_mappings[@]}" | sed '$ s/,$//')
    
    if [[ -z "$column_list" ]]; then
        echo "   ❌ Failed to get columns for $table"
        continue
    fi
    
    # Create the view
    create_view_sql="
    CREATE OR REPLACE VIEW $SCHEMA.$view_name AS
    SELECT 
$column_list
    FROM $SCHEMA.\"$table\";
    "
    
    result=$(docker exec postgres18 psql -U $POSTGRES_USER -d $DATABASE -c "$create_view_sql" 2>&1)
    
    if echo "$result" | grep -q "CREATE VIEW"; then
        echo "   ✅ View created: $SCHEMA.$view_name"
        ((view_count++))
    else
        echo "   ❌ Failed to create view: $result"
    fi
    
    echo ""
    
done <<< "$tables"

echo "=========================================================================="
echo "✅ Successfully created $view_count snake_case views"
echo "=========================================================================="
echo ""
echo "You can now query using snake_case:"
echo ""
echo "  SELECT employee_id, first_name, last_name"
echo "  FROM dbo.employees"
echo "  WHERE employee_id > 1000;"
echo ""
echo "To list all views:"
echo "  docker exec postgres18 psql -U $POSTGRES_USER -d $DATABASE -c \\"
echo "    \"SELECT table_name FROM information_schema.views WHERE table_schema = '$SCHEMA';\""
echo ""

