INSERT INTO reservation_daily_units (day_number, team_id, scope, scope_id, units)
SELECT day_number, team_id, 'member', member_id, SUM(units) FROM reservations GROUP BY day_number, team_id, member_id
UNION ALL
SELECT day_number, team_id, 'pool', pool_id, SUM(units) FROM reservations GROUP BY day_number, team_id, pool_id
UNION ALL
SELECT day_number, team_id, 'lent', source_pool_id, SUM(units) FROM reservations
WHERE source_pool_id IS NOT NULL AND source_pool_id <> pool_id GROUP BY day_number, team_id, source_pool_id
UNION ALL
SELECT g.day_number, r.team_id, 'group', g.group_id, SUM(r.units) FROM reservation_groups g INNER JOIN reservations r ON r.id = g.reservation_id
GROUP BY g.day_number, r.team_id, g.group_id;
