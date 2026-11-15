-- Canvas files are MP4: without video/mp4 among the allowed types every Canvas upload was refused
-- by storage and reported as "Impossible de sauvegarder le fichier".
update storage.buckets
set allowed_mime_types = array(select distinct unnest(coalesce(allowed_mime_types, '{}'::text[]) || array['video/mp4']))
where id = 'ketvoice-public-media';
