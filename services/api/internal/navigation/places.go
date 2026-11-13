package navigation

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"sort"
	"strings"
	"unicode"
	"unicode/utf8"
)

type Place struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Kind      string  `json:"kind"`
	Locality  string  `json:"locality"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}
type PlaceSearcher interface {
	Search(context.Context, string) ([]Place, error)
}

var ErrSearchBusy = errors.New("place search busy")

type indexedPlace struct {
	Place
	Aliases string `json:"aliases"`
	terms   string
}
type Gazetteer struct {
	places []indexedPlace
	slots  chan struct{}
}

func normalizeName(s string) string {
	return strings.Join(strings.FieldsFunc(strings.ToLower(s), func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsNumber(r) }), " ")
}
func ValidPlaceQuery(q string) bool {
	n := utf8.RuneCountInString(strings.TrimSpace(q))
	return n >= 2 && n <= 120 && len(q) <= 512 && normalizeName(q) != ""
}

// The file is a separately licensed, immutable OSM-derived name index, never user data.
func LoadGazetteer(path string) (*Gazetteer, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || info.Size() > 32<<20 {
		return nil, errors.New("place index exceeds size bound")
	}
	var input struct {
		Version int            `json:"version"`
		Region  string         `json:"region"`
		Places  []indexedPlace `json:"places"`
	}
	decoder := json.NewDecoder(io.LimitReader(f, (32<<20)+1))
	if err := decoder.Decode(&input); err != nil {
		return nil, err
	}
	if input.Version != 1 || input.Region != "Kenya" || len(input.Places) == 0 || len(input.Places) > 200000 {
		return nil, errors.New("invalid regional place index")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("trailing place index data")
	}
	ids := make(map[string]bool, len(input.Places))
	for i := range input.Places {
		p := &input.Places[i]
		if p.ID == "" || ids[p.ID] || p.Name == "" || len(p.Name) > 800 || len(p.Aliases) > 1600 || len(p.Locality) > 800 || !(Point{Latitude: p.Latitude, Longitude: p.Longitude}).Valid() {
			return nil, errors.New("invalid indexed place")
		}
		ids[p.ID] = true
		p.terms = normalizeName(p.Name + " " + p.Aliases + " " + p.Locality)
	}
	return &Gazetteer{places: input.Places, slots: make(chan struct{}, 4)}, nil
}
func (g *Gazetteer) Search(ctx context.Context, query string) ([]Place, error) {
	select {
	case g.slots <- struct{}{}:
		defer func() { <-g.slots }()
	default:
		return nil, ErrSearchBusy
	}
	q := normalizeName(query)
	tokens := strings.Fields(q)
	type match struct {
		place Place
		score int
	}
	matches := make([]match, 0, 32)
	for i, p := range g.places {
		if i%256 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		found := true
		for _, t := range tokens {
			if !strings.Contains(p.terms, t) {
				found = false
				break
			}
		}
		if !found {
			continue
		}
		score := 0
		name := normalizeName(p.Name)
		if name == q {
			score += 100
		} else if strings.HasPrefix(name, q) {
			score += 50
		}
		switch p.Kind {
		case "city":
			score += 20
		case "town":
			score += 15
		case "village", "suburb":
			score += 10
		}
		matches = append(matches, match{p.Place, score})
	}
	sort.Slice(matches, func(i, j int) bool {
		if matches[i].score != matches[j].score {
			return matches[i].score > matches[j].score
		}
		if matches[i].place.Name != matches[j].place.Name {
			return matches[i].place.Name < matches[j].place.Name
		}
		return matches[i].place.ID < matches[j].place.ID
	})
	result := make([]Place, 0, 10)
	seen := map[string]bool{}
	for _, m := range matches {
		key := normalizeName(m.place.Name + " " + m.place.Kind + " " + m.place.Locality)
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, m.place)
		if len(result) == 10 {
			break
		}
	}
	return result, nil
}
