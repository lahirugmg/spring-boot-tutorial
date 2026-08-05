-- Seed data. Explicit ids here, then the sequences are pushed past them so Hibernate
-- never collides with a seeded row.

INSERT INTO author (id, name, country) VALUES
    (1, 'Martin Fowler',    'United Kingdom'),
    (2, 'Robert C. Martin', 'United States'),
    (3, 'Joshua Bloch',     'United States'),
    (4, 'Kent Beck',        'United States'),
    (5, 'Eric Evans',       'United States'),
    (6, 'Gojko Adzic',      'Serbia');

INSERT INTO book (id, title, isbn, price, published_on, stock, author_id) VALUES
    (1,  'Refactoring',                                 '978-0134757599', 47.99, '2018-11-20', 12, 1),
    (2,  'Patterns of Enterprise Application Architecture','978-0321127426', 59.99, '2002-11-15',  5, 1),
    (3,  'UML Distilled',                               '978-0321193681', 39.99, '2003-09-25',  8, 1),
    (4,  'NoSQL Distilled',                             '978-0321826626', 34.99, '2012-08-08',  3, 1),
    (5,  'Clean Code',                                  '978-0132350884', 44.99, '2008-08-01', 25, 2),
    (6,  'Clean Architecture',                          '978-0134494166', 41.99, '2017-09-10', 17, 2),
    (7,  'The Clean Coder',                             '978-0137081073', 36.99, '2011-05-13',  9, 2),
    (8,  'Agile Software Development',                  '978-0135974445', 64.99, '2002-10-15',  2, 2),
    (9,  'Effective Java',                              '978-0134685991', 45.99, '2017-12-27', 30, 3),
    (10, 'Java Puzzlers',                               '978-0321336781', 42.99, '2005-07-04',  4, 3),
    (11, 'Java Concurrency in Practice',                '978-0321349606', 49.99, '2006-05-19', 15, 3),
    (12, 'Test Driven Development: By Example',         '978-0321146533', 37.99, '2002-11-08', 11, 4),
    (13, 'Extreme Programming Explained',               '978-0321278654', 38.99, '2004-11-16',  6, 4),
    (14, 'Implementation Patterns',                     '978-0321413093', 35.99, '2007-11-02',  1, 4),
    (15, 'Tidy First?',                                 '978-1098151249', 24.99, '2023-12-19', 20, 4),
    (16, 'Domain-Driven Design',                        '978-0321125217', 62.99, '2003-08-22', 14, 5),
    (17, 'Domain-Driven Design Reference',              '978-1457501197', 19.99, '2014-10-01',  7, 5),
    (18, 'Specification by Example',                    '978-1617290084', 43.99, '2011-06-15',  5, 6),
    (19, 'Impact Mapping',                              '978-0955683644', 21.99, '2012-10-01',  8, 6),
    (20, 'Fifty Quick Ideas to Improve Your Tests',     '978-0993088107', 18.99, '2015-01-20',  0, 6);

-- Move each sequence well past the seeded ids.
ALTER SEQUENCE author_seq RESTART WITH 1000;
ALTER SEQUENCE book_seq   RESTART WITH 1000;
ALTER SEQUENCE audit_seq  RESTART WITH 1000;
