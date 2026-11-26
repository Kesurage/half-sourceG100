# half-sourceG100 tier2 library + tests (host build; the same sources compile
# for aarch64 android inside the engine build later)
CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Werror

SRCS := src/keyvalues.cpp src/modclassifier.cpp src/vpk.cpp src/mountplanner.cpp
HDRS := $(wildcard include/hsg100/*.hpp)
LIB := libtier2.a

.PHONY: all test clean

all: $(LIB) test_tier2

$(LIB): $(SRCS) $(HDRS)
	$(CXX) $(CXXFLAGS) -Iinclude -c $(SRCS)
	ar rcs $(LIB) keyvalues.o modclassifier.o vpk.o mountplanner.o

test_tier2: tests/test_tier2.cpp $(LIB)
	$(CXX) $(CXXFLAGS) -Iinclude tests/test_tier2.cpp $(LIB) -o test_tier2

test: test_tier2
	./test_tier2

clean:
	rm -f *.o $(LIB) test_tier2
