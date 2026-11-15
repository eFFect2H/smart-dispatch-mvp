INSERT INTO teams
    (name, capacity, start_latitude, start_longitude, shift_start_minute, shift_end_minute, active)
VALUES
    ('Центральная бригада', 3, 52.5200, 13.4050, 480, 1080, TRUE),
    ('Западная бригада',    3, 52.5251, 13.3695, 480, 1080, TRUE);

INSERT INTO transport_requests
    (title, address, latitude, longitude, window_start_minute, window_end_minute,
     service_duration_minutes, demand, status)
VALUES
    ('Проверка объекта A', 'Brandenburg Gate, Berlin', 52.5163, 13.3777, 510, 660, 20, 1, 'NEW'),
    ('Доставка оборудования B', 'Alexanderplatz, Berlin', 52.5211, 13.4132, 570, 780, 15, 1, 'NEW'),
    ('Плановый осмотр C', 'Checkpoint Charlie, Berlin', 52.5074, 13.3903, 600, 780, 20, 1, 'NEW'),
    ('Ремонтный вызов D', 'Potsdamer Platz, Berlin', 52.5096, 13.3759, 480, 660, 15, 1, 'NEW'),
    ('Обслуживание E', 'East Side Gallery, Berlin', 52.5054, 13.4394, 720, 900, 30, 1, 'NEW');
